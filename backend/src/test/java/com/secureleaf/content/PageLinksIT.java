package com.secureleaf.content;

import com.secureleaf.commerce.AbstractCommerceIT;
import com.secureleaf.common.storage.InMemoryStorageService;
import com.secureleaf.content.entity.DocumentVersion;
import com.secureleaf.content.service.DocumentProcessingService;
import com.secureleaf.content.service.PageLinkService;
import com.secureleaf.creator.entity.JobStatus;
import com.secureleaf.creator.entity.ProcessingJob;
import com.secureleaf.creator.repository.ProcessingJobRepository;
import com.secureleaf.marketplace.entity.Product;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;

/** V8 — PDF links: extracted by the pipeline, backfilled for old uploads, served to the reader. */
class PageLinksIT extends AbstractCommerceIT {

    @Autowired private DocumentProcessingService pipeline;
    @Autowired private PageLinkService pageLinkService;
    @Autowired private ProcessingJobRepository jobRepository;
    @Autowired private InMemoryStorageService storage;

    /** 2 pages, 600×800 pt. Page 1: a web link, a javascript: link (must be dropped), a link to page 2. */
    private static byte[] pdfWithLinks() throws Exception {
        try (PDDocument pdf = new PDDocument()) {
            PDPage first = new PDPage(new PDRectangle(600, 800));
            PDPage second = new PDPage(new PDRectangle(600, 800));
            pdf.addPage(first);
            pdf.addPage(second);

            PDAnnotationLink web = new PDAnnotationLink();
            web.setRectangle(new PDRectangle(60, 700, 300, 20));
            PDActionURI uri = new PDActionURI();
            uri.setURI("https://example.com/further-reading");
            web.setAction(uri);

            PDAnnotationLink evil = new PDAnnotationLink();
            evil.setRectangle(new PDRectangle(60, 600, 300, 20));
            PDActionURI js = new PDActionURI();
            js.setURI("javascript:alert(1)");
            evil.setAction(js);

            PDAnnotationLink toPageTwo = new PDAnnotationLink();
            toPageTwo.setRectangle(new PDRectangle(60, 500, 100, 20));
            PDPageFitDestination destination = new PDPageFitDestination();
            destination.setPage(second);
            PDActionGoTo goTo = new PDActionGoTo();
            goTo.setDestination(destination);
            toPageTwo.setAction(goTo);

            first.setAnnotations(new ArrayList<PDAnnotation>(List.of(web, evil, toPageTwo)));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            pdf.save(out);
            return out.toByteArray();
        }
    }

    private DocumentVersion versionOf(Product product) {
        return documentVersionRepository.findFirstByProductIdOrderByVersionNumberDesc(product.getId()).orElseThrow();
    }

    private List<Map<String, Object>> linkRows(long versionId) {
        return jdbcTemplate.queryForList("""
                SELECT cp.page_number, l.link_type, l.target_url, l.target_page,
                       l.left_ratio, l.top_ratio, l.width_ratio, l.height_ratio
                FROM content_page_links l JOIN content_pages cp ON cp.id = l.content_page_id
                WHERE cp.document_version_id = ? ORDER BY l.id
                """, versionId);
    }

    @Test
    void pipeline_extractsSafeLinks_andStampsTheVersion() throws Exception {
        DocumentVersion version = versionOf(paidProduct);
        storage.put(version.getRawMinioBucket(), version.getRawMinioObjectKey(), pdfWithLinks(), "application/pdf");
        ProcessingJob job = new ProcessingJob();
        job.setProduct(paidProduct);
        job.setDocumentVersion(version);
        job.setStatus(JobStatus.PROCESSING);
        Long jobId = jobRepository.save(job).getId();

        pipeline.processAsync(jobId);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(jobRepository.findById(jobId).orElseThrow().getStatus()).isEqualTo(JobStatus.COMPLETED));

        List<Map<String, Object>> links = linkRows(version.getId());
        assertThat(links).hasSize(2); // the javascript: link was dropped
        assertThat(links.get(0)).containsEntry("page_number", 1).containsEntry("link_type", "URL")
                .containsEntry("target_url", "https://example.com/further-reading");
        assertThat((Double) links.get(0).get("left_ratio")).isCloseTo(0.1, within(1e-6));
        assertThat((Double) links.get(0).get("top_ratio")).isCloseTo(80.0 / 800, within(1e-6));
        assertThat(links.get(1)).containsEntry("link_type", "PAGE").containsEntry("target_page", 2);
        assertThat(documentVersionRepository.findById(version.getId()).orElseThrow().getLinksExtractedAt()).isNotNull();
    }

    @Test
    void backfill_addsLinksToAnOldUpload_once() throws Exception {
        DocumentVersion version = versionOf(freeProduct);
        storage.put(version.getRawMinioBucket(), version.getRawMinioObjectKey(), pdfWithLinks(), "application/pdf");
        // Processed before V8: pages exist, links don't, links_extracted_at is NULL.
        for (int page = 1; page <= 2; page++) {
            jdbcTemplate.update("""
                    INSERT INTO content_pages (document_version_id, page_number, bucket_name, minio_object_key)
                    VALUES (?, ?, 'secureleaf-tiles', ?)
                    """, version.getId(), page, "old/" + page + ".png");
        }
        jdbcTemplate.update("UPDATE document_versions SET processed_at = now() WHERE id = ?", version.getId());

        pageLinkService.backfill(version.getId());
        pageLinkService.backfill(version.getId()); // second run: already stamped, adds nothing

        assertThat(linkRows(version.getId())).hasSize(2);
        assertThat(documentVersionRepository.findById(version.getId()).orElseThrow().getLinksExtractedAt()).isNotNull();
    }

    @Test
    void backfill_marksVersionDone_evenWhenTheRawPdfIsMissing() {
        DocumentVersion version = versionOf(freeProduct); // nothing stored under its raw key

        pageLinkService.backfill(version.getId());

        assertThat(documentVersionRepository.findById(version.getId()).orElseThrow().getLinksExtractedAt()).isNotNull();
    }

    @Test
    void viewerPageUrl_returnsThePagesLinks_toTheEntitledBuyer() {
        // Free product: ordering it grants the entitlement straight away.
        asBuyer.document("mutation($p: ID!, $k: String!) { initiateOrder(productId: $p, idempotencyKey: $k) { order { status } } }")
                .variable("p", freeProduct.getId()).variable("k", newKey())
                .execute().path("initiateOrder.order.status").entity(String.class).isEqualTo("COMPLETED");

        DocumentVersion version = versionOf(freeProduct);
        long page1 = jdbcTemplate.queryForObject("""
                INSERT INTO content_pages (document_version_id, page_number, bucket_name, minio_object_key)
                VALUES (?, 1, 'secureleaf-tiles', 'k/1.png') RETURNING id
                """, Long.class, version.getId());
        jdbcTemplate.update("""
                INSERT INTO content_page_links (content_page_id, left_ratio, top_ratio, width_ratio, height_ratio, link_type, target_url)
                VALUES (?, 0.1, 0.2, 0.3, 0.05, 'URL', 'https://example.com')
                """, page1);
        jdbcTemplate.update("""
                INSERT INTO content_page_links (content_page_id, left_ratio, top_ratio, width_ratio, height_ratio, link_type, target_page)
                VALUES (?, 0.1, 0.5, 0.2, 0.05, 'PAGE', 4)
                """, page1);

        String sessionToken = asBuyer.document("""
                        mutation($p: ID!) { startViewerSession(productId: $p, deviceFingerprint: "it") { sessionToken } }
                        """).variable("p", freeProduct.getId())
                .execute().path("startViewerSession.sessionToken").entity(String.class).get();

        var response = asBuyer.document("""
                        query($t: String!, $n: Int!) {
                          viewerPageUrl(sessionToken: $t, pageNumber: $n) {
                            url
                            links { left top width height type url targetPage }
                          }
                        }
                        """).variable("t", sessionToken).variable("n", 1).execute();

        response.path("viewerPageUrl.links").entityList(Object.class).hasSize(2);
        response.path("viewerPageUrl.links[0].type").entity(String.class).isEqualTo("URL");
        response.path("viewerPageUrl.links[0].url").entity(String.class).isEqualTo("https://example.com");
        response.path("viewerPageUrl.links[0].left").entity(Double.class).isEqualTo(0.1);
        response.path("viewerPageUrl.links[1].type").entity(String.class).isEqualTo("PAGE");
        response.path("viewerPageUrl.links[1].targetPage").entity(Integer.class).isEqualTo(4);
        response.path("viewerPageUrl.links[1].url").valueIsNull();

        // A page without links → an empty list, never null.
        asBuyer.document("""
                        query($t: String!) { viewerPageUrl(sessionToken: $t, pageNumber: 2) { links { url } } }
                        """).variable("t", sessionToken).execute()
                .path("viewerPageUrl.links").entityList(Object.class).hasSize(0);
    }
}
