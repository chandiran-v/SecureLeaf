package com.secureleaf.content;

import com.secureleaf.auth.entity.Role;
import com.secureleaf.auth.entity.User;
import com.secureleaf.commerce.AbstractCommerceIT;
import com.secureleaf.commerce.repository.EntitlementRepository;
import com.secureleaf.commerce.service.EntitlementMigrationJob;
import com.secureleaf.commerce.service.EntitlementMigrationService;
import com.secureleaf.common.storage.InMemoryStorageService;
import com.secureleaf.content.dto.UploadResponseDto;
import com.secureleaf.content.entity.ContentPage;
import com.secureleaf.content.entity.DocumentVersion;
import com.secureleaf.content.entity.UpdatePolicy;
import com.secureleaf.content.repository.ContentPageRepository;
import com.secureleaf.content.service.DocumentProcessingService;
import com.secureleaf.content.service.DocumentVersionService;
import com.secureleaf.creator.entity.JobStatus;
import com.secureleaf.creator.entity.ProcessingJob;
import com.secureleaf.creator.repository.ProcessingJobRepository;
import com.secureleaf.marketplace.entity.Category;
import com.secureleaf.marketplace.entity.Product;
import com.secureleaf.marketplace.entity.ProductStatus;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 15 — document versioning, end to end against the real pipeline (in-memory storage).
 *
 * How the in-flight state is made deterministic: the suite's real {@code ProcessingJobWorker}
 * polls every 5s, so a test that wants to observe "v2 is processing" must not leave its job
 * QUEUED. {@link #stageVersion} therefore creates the version and flips its job to PROCESSING in
 * ONE transaction (the poller never sees it QUEUED), and {@link #runJob} then drives the pipeline
 * explicitly when the test is ready.
 */
class DocumentVersioningIT extends AbstractCommerceIT {

    @Autowired private DocumentVersionService versionService;
    @Autowired private DocumentProcessingService pipeline;
    @Autowired private ProcessingJobRepository jobRepository;
    @Autowired private ContentPageRepository pageRepository;
    @Autowired private EntitlementRepository entitlementRepository;
    @Autowired private InMemoryStorageService storage;
    @Autowired private PlatformTransactionManager txManager;

    private static final String LIBRARY = "query { myLibrary { versionNumber newEditionAvailable status } }";
    private static final String VERSIONS = """
            query($id: ID!) { productVersions(productId: $id) {
              id versionNumber status updatePolicy buyerCount current pageCount failureReason migrationPending } }
            """;
    private static final String RETIRE = """
            mutation($p: ID!, $v: ID!) { retireDocumentVersion(productId: $p, versionId: $v) }
            """;
    private static final String RETRY = """
            mutation($p: ID!, $v: ID) { retryProcessing(productId: $p, versionId: $v) { status } }
            """;

    // ── fixtures ─────────────────────────────────────────────────────────────

    private static byte[] pdf(int pages) {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) doc.addPage(new PDPage());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A FREE, LIVE product whose v1 (1 page) has been through the real pipeline. */
    private Product productWithV1() {
        Category category = categoryRepository.findAll().get(0);
        Product p = new Product();
        p.setCreator(creator);
        p.setCategory(category);
        p.setTitle("Versioned Guide");
        p.setSlug("versioned-guide-" + System.nanoTime());
        p.setDescription("desc");
        p.setPricePaise(0L);
        p.setFreePreviewPages(5);
        p.setStatus(ProductStatus.PROCESSING);
        p = productRepository.save(p);

        DocumentVersion v = new DocumentVersion();
        v.setProduct(p);
        v.setVersionNumber(1);
        v.setOriginalFilename("v1.pdf");
        v.setFileSizeBytes(100L);
        v.setRawMinioBucket("raw");
        v.setRawMinioObjectKey("raw/v1-" + p.getId());
        v = documentVersionRepository.save(v);
        storage.put("raw", v.getRawMinioObjectKey(), pdf(1), "application/pdf");

        ProcessingJob job = new ProcessingJob();
        job.setProduct(p);
        job.setDocumentVersion(v);
        job.setStatus(JobStatus.PROCESSING);
        job = jobRepository.save(job);
        runJob(job.getId());

        Product live = productRepository.findById(p.getId()).orElseThrow();
        assertThat(live.getStatus()).isEqualTo(ProductStatus.LIVE);
        return live;
    }

    /** Creates version N+1 with its job already PROCESSING (never visible as QUEUED). Returns the job id. */
    private Long stageVersion(Product product, byte[] pdfBytes, UpdatePolicy policy) {
        String key = "raw/staged-" + System.nanoTime();
        storage.put("raw", key, pdfBytes, "application/pdf");
        return new TransactionTemplate(txManager).execute(s -> {
            UploadResponseDto r = versionService.createVersion(product.getId(), creator.getId(), "next.pdf",
                    pdfBytes.length, "raw", key, policy);
            ProcessingJob job = jobRepository.findById(r.jobId()).orElseThrow();
            job.setStatus(JobStatus.PROCESSING);
            return job.getId();
        });
    }

    private void runJob(Long jobId) {
        pipeline.processAsync(jobId);   // @Async — completes on another thread
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(jobRepository.findById(jobId).orElseThrow().getStatus())
                        .isIn(JobStatus.COMPLETED, JobStatus.FAILED, JobStatus.QUEUED));
    }

    private Long currentVersionNumber(Product p) {
        return jdbcTemplate.queryForObject("""
                SELECT v.version_number FROM products p JOIN document_versions v ON v.id = p.current_document_version_id
                WHERE p.id = ?""", Long.class, p.getId());
    }

    private void buyFree(HttpGraphQlTester as, Product product) {
        as.document(INITIATE).variable("productId", product.getId()).variable("key", newKey()).execute();
    }

    private Long entitlementVersionNumber(User who, Product p) {
        return jdbcTemplate.queryForObject("""
                SELECT v.version_number FROM entitlements e JOIN document_versions v ON v.id = e.document_version_id
                WHERE e.buyer_id = ? AND e.product_id = ? AND e.status = 'ACTIVE'""", Long.class, who.getId(), p.getId());
    }

    private EntitlementMigrationService migrationService(int chunkSize) {
        return new EntitlementMigrationService(entitlementRepository, documentVersionRepository, txManager, chunkSize);
    }

    private int entitlementsOnVersion(Product p, int versionNumber) {
        return count("""
                SELECT count(*) FROM entitlements e JOIN document_versions v ON v.id = e.document_version_id
                WHERE e.product_id = ? AND v.version_number = ? AND e.status = 'ACTIVE'""", p.getId(), versionNumber);
    }

    private Long versionId(Product p, int n) {
        return documentVersionRepository.findByProductIdAndVersionNumber(p.getId(), n).orElseThrow().getId();
    }

    // ── AC2: v2 uploads without disturbing the LIVE product ──────────────────

    @Test
    void newVersion_keepsProductLiveOnV1_untilProcessed_thenPointerMovesAndPreviewServesV2() throws Exception {
        Product product = productWithV1();
        assertThat(currentVersionNumber(product)).isEqualTo(1L);

        Long jobId = stageVersion(product, pdf(3), UpdatePolicy.NEW_BUYERS_ONLY);

        // v2 exists and is "processing", but the product is still LIVE on v1 and previews v1 only.
        assertThat(productRepository.findById(product.getId()).orElseThrow().getStatus()).isEqualTo(ProductStatus.LIVE);
        assertThat(currentVersionNumber(product)).isEqualTo(1L);
        mockMvc.perform(get("/api/products/{id}/preview/{page}", product.getId(), 1)).andExpect(status().isOk());
        mockMvc.perform(get("/api/products/{id}/preview/{page}", product.getId(), 2)).andExpect(status().isNotFound());

        runJob(jobId);

        assertThat(jobRepository.findById(jobId).orElseThrow().getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(currentVersionNumber(product)).isEqualTo(2L);
        assertThat(productRepository.findById(product.getId()).orElseThrow().getStatus()).isEqualTo(ProductStatus.LIVE);
        mockMvc.perform(get("/api/products/{id}/preview/{page}", product.getId(), 3)).andExpect(status().isOk());

        // The creator is told (and the text says "version 2", not a first-publish message).
        assertThat(count("SELECT count(*) FROM notifications WHERE recipient_id = ? AND title LIKE 'Version 2 is ready%'",
                creator.getId())).isEqualTo(1);
    }

    // ── AC3: NEW_BUYERS_ONLY ─────────────────────────────────────────────────

    @Test
    void newBuyersOnly_existingBuyerKeepsV1_newBuyerGetsV2_andLibraryHintsAtTheNewEdition() {
        Product product = productWithV1();
        buyFree(asBuyer, product);                                            // bought while v1 was current
        runJob(stageVersion(product, pdf(3), UpdatePolicy.NEW_BUYERS_ONLY));  // v2 becomes current

        // The (disabled-in-tests) poller would run now; it has nothing to do for NEW_BUYERS_ONLY.
        new EntitlementMigrationJob(documentVersionRepository, migrationService(500)).runOnce();

        assertThat(entitlementVersionNumber(buyer, product)).isEqualTo(1L);
        buyFree(asOtherBuyer, product);
        assertThat(entitlementVersionNumber(otherBuyer, product)).isEqualTo(2L);

        asBuyer.document(LIBRARY).execute()
                .path("myLibrary[0].versionNumber").entity(Integer.class).isEqualTo(1)
                .path("myLibrary[0].newEditionAvailable").entity(Boolean.class).isEqualTo(true);
        asOtherBuyer.document(LIBRARY).execute()
                .path("myLibrary[0].versionNumber").entity(Integer.class).isEqualTo(2)
                .path("myLibrary[0].newEditionAvailable").entity(Boolean.class).isEqualTo(false);
    }

    // ── AC4: FREE_UPDATE_FOR_EXISTING — chunked, idempotent, resumable ──────

    @Test
    void freeUpdate_movesEveryActiveEntitlement_idempotently_andResumesAfterACrashBetweenChunks() {
        Product product = productWithV1();
        List<User> buyers = java.util.stream.IntStream.rangeClosed(1, 5)
                .mapToObj(i -> makeUser("bulk" + i + "@example.com", "Bulk " + i, Role.BUYER)).toList();
        buyers.forEach(b -> buyFree(tester(b), product));
        // A refunded (REVOKED) buyer must not be dragged along.
        jdbcTemplate.update("UPDATE entitlements SET status = 'REVOKED', revoked_at = now() WHERE buyer_id = ?",
                buyers.get(4).getId());

        runJob(stageVersion(product, pdf(2), UpdatePolicy.FREE_UPDATE_FOR_EXISTING));
        Long v2 = versionId(product, 2);
        assertThat(entitlementsOnVersion(product, 1)).isEqualTo(4);

        // Chunk size 2, and "crash" after the first chunk.
        EntitlementMigrationService service = migrationService(2);
        assertThat(service.migrate(v2, 1)).isEqualTo(2);
        assertThat(entitlementsOnVersion(product, 2)).isEqualTo(2);
        assertThat(entitlementsOnVersion(product, 1)).isEqualTo(2);
        assertThat(documentVersionRepository.findById(v2).orElseThrow().getEntitlementsMigratedAt())
                .as("an interrupted migration must not be stamped complete").isNull();

        // The next poller tick resumes exactly where it stopped.
        new EntitlementMigrationJob(documentVersionRepository, service).runOnce();
        assertThat(entitlementsOnVersion(product, 2)).isEqualTo(4);
        assertThat(entitlementsOnVersion(product, 1)).isZero();
        assertThat(documentVersionRepository.findById(v2).orElseThrow().getEntitlementsMigratedAt()).isNotNull();

        // Idempotent: running it again changes nothing.
        assertThat(service.migrate(v2)).isZero();
        new EntitlementMigrationJob(documentVersionRepository, service).runOnce();
        assertThat(entitlementsOnVersion(product, 2)).isEqualTo(4);

        // The revoked entitlement stayed revoked, on v1.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status::text FROM entitlements WHERE buyer_id = ?", String.class, buyers.get(4).getId()))
                .isEqualTo("REVOKED");
        asBuyer.document(LIBRARY).execute().path("myLibrary").entityList(Object.class).hasSize(0);
    }

    @Test
    void freeUpdate_skipsAVersionThatIsNoLongerCurrent() {
        Product product = productWithV1();
        buyFree(asBuyer, product);
        runJob(stageVersion(product, pdf(2), UpdatePolicy.FREE_UPDATE_FOR_EXISTING));   // v2
        runJob(stageVersion(product, pdf(3), UpdatePolicy.NEW_BUYERS_ONLY));            // v3 supersedes it

        // v2's migration never ran, and v2 is no longer current: moving buyers to it would go backwards.
        assertThat(migrationService(500).migrate(versionId(product, 2))).isZero();
        assertThat(entitlementVersionNumber(buyer, product)).isEqualTo(1L);
    }

    // ── AC5: a failed v2 leaves the pointer on v1, and retry works ───────────

    @Test
    void failedVersion_leavesPointerOnV1_andRetryCompletesIt() {
        Product product = productWithV1();
        Long jobId = stageVersion(product, "%PDF-but-not-really".getBytes(), UpdatePolicy.NEW_BUYERS_ONLY);
        ProcessingJob job = jobRepository.findById(jobId).orElseThrow();
        job.setMaxRetries(1);   // fail on the first attempt
        jobRepository.save(job);

        runJob(jobId);

        assertThat(jobRepository.findById(jobId).orElseThrow().getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(currentVersionNumber(product)).isEqualTo(1L);
        assertThat(productRepository.findById(product.getId()).orElseThrow().getStatus())
                .as("a failed v2 must not take the product down").isEqualTo(ProductStatus.LIVE);
        asCreator.document(VERSIONS).variable("id", product.getId()).execute()
                .path("productVersions[0].status").entity(String.class).isEqualTo("FAILED")
                .path("productVersions[0].current").entity(Boolean.class).isEqualTo(false)
                .path("productVersions[1].current").entity(Boolean.class).isEqualTo(true);

        // Fix the raw file, then retry THAT version (the product itself is not FAILED).
        DocumentVersion v2 = documentVersionRepository.findByProductIdAndVersionNumber(product.getId(), 2).orElseThrow();
        storage.put(v2.getRawMinioBucket(), v2.getRawMinioObjectKey(), pdf(2), "application/pdf");
        asCreator.document(RETRY).variable("p", product.getId()).variable("v", v2.getId()).execute()
                .path("retryProcessing.status").entity(String.class).isEqualTo("LIVE");

        // The regular poller picks the re-queued job up, exactly like a new upload.
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(currentVersionNumber(product)).isEqualTo(2L));
    }

    @Test
    void retry_ofAVersionThatDidNotFail_isRejected() {
        Product product = productWithV1();
        asCreator.document(RETRY).variable("p", product.getId()).variable("v", versionId(product, 1)).execute()
                .errors().expect(e -> "INVALID_STATE_TRANSITION".equals(e.getExtensions().get("code"))).verify();
    }

    // ── AC6: versions with entitlements cannot be retired; their tiles survive ──

    @Test
    void retire_isRejectedWhileAnEntitlementPointsAtTheVersion_andItsTilesAreKept() {
        Product product = productWithV1();
        buyFree(asBuyer, product);
        runJob(stageVersion(product, pdf(2), UpdatePolicy.NEW_BUYERS_ONLY));   // buyer stays on v1
        Long v1 = versionId(product, 1);
        List<ContentPage> v1Tiles = pageRepository.findByDocumentVersionIdOrderByPageNumber(v1);
        assertThat(v1Tiles).isNotEmpty();

        asCreator.document(RETIRE).variable("p", product.getId()).variable("v", v1).execute()
                .errors().expect(e -> "INVALID_STATE_TRANSITION".equals(e.getExtensions().get("code"))).verify();

        assertThat(documentVersionRepository.findById(v1).orElseThrow().getRetiredAt()).isNull();
        v1Tiles.forEach(t -> assertThat(storage.exists(t.getBucketName(), t.getMinioObjectKey())).isTrue());

        // A REVOKED entitlement still references the row (FK, audit): still not retirable.
        jdbcTemplate.update("UPDATE entitlements SET status = 'REVOKED', revoked_at = now() WHERE buyer_id = ?", buyer.getId());
        asCreator.document(RETIRE).variable("p", product.getId()).variable("v", v1).execute()
                .errors().expect(e -> "INVALID_STATE_TRANSITION".equals(e.getExtensions().get("code"))).verify();

        // The current version can never be retired either.
        asCreator.document(RETIRE).variable("p", product.getId()).variable("v", versionId(product, 2)).execute()
                .errors().expect(e -> "INVALID_STATE_TRANSITION".equals(e.getExtensions().get("code"))).verify();
    }

    @Test
    void retire_succeedsOnceNobodyPointsAtTheVersion_andRemovesOnlyThatVersionsTiles() {
        Product product = productWithV1();
        buyFree(asBuyer, product);
        runJob(stageVersion(product, pdf(2), UpdatePolicy.FREE_UPDATE_FOR_EXISTING));
        migrationService(500).migrate(versionId(product, 2));   // buyer moved to v2
        Long v1 = versionId(product, 1);
        List<ContentPage> v1Tiles = pageRepository.findByDocumentVersionIdOrderByPageNumber(v1);
        List<ContentPage> v2Tiles = pageRepository.findByDocumentVersionIdOrderByPageNumber(versionId(product, 2));

        asCreator.document(RETIRE).variable("p", product.getId()).variable("v", v1).execute()
                .path("retireDocumentVersion").entity(Boolean.class).isEqualTo(true);

        assertThat(documentVersionRepository.findById(v1).orElseThrow().getRetiredAt()).isNotNull();
        v1Tiles.forEach(t -> assertThat(storage.exists(t.getBucketName(), t.getMinioObjectKey())).isFalse());
        v2Tiles.forEach(t -> assertThat(storage.exists(t.getBucketName(), t.getMinioObjectKey())).isTrue());
        asCreator.document(VERSIONS).variable("id", product.getId()).execute()
                .path("productVersions[1].status").entity(String.class).isEqualTo("RETIRED");
    }

    // ── version history (D7) ─────────────────────────────────────────────────

    @Test
    void versionHistory_listsNumberStatusPolicyAndBuyersOnEachVersion() {
        Product product = productWithV1();
        buyFree(asBuyer, product);
        buyFree(asOtherBuyer, product);
        runJob(stageVersion(product, pdf(3), UpdatePolicy.NEW_BUYERS_ONLY));
        stageVersion(product, pdf(4), UpdatePolicy.FREE_UPDATE_FOR_EXISTING);   // v3 still processing

        asCreator.document(VERSIONS).variable("id", product.getId()).execute()
                .path("productVersions[*].versionNumber").entityList(Integer.class).containsExactly(3, 2, 1)
                .path("productVersions[*].status").entityList(String.class).containsExactly("PROCESSING", "READY", "READY")
                .path("productVersions[*].updatePolicy").entityList(String.class)
                .containsExactly("FREE_UPDATE_FOR_EXISTING", "NEW_BUYERS_ONLY", "NEW_BUYERS_ONLY")
                .path("productVersions[*].buyerCount").entityList(Integer.class).containsExactly(0, 0, 2)
                .path("productVersions[*].current").entityList(Boolean.class).containsExactly(false, true, false);
    }

    // ── AC7 + REST contract: owner-only, same validation as the first upload ──

    private static MockMultipartFile file(byte[] content) {
        return new MockMultipartFile("file", "next.pdf", MediaType.APPLICATION_PDF_VALUE, content);
    }

    @Test
    void uploadVersion_rest_acceptsOwnersPdf_createsNextVersionWithPolicy_andRejectsASecondWhileOneIsInFlight() throws Exception {
        Product product = productWithV1();
        String token = jwtService.generateAccessToken(creator);

        mockMvc.perform(multipart("/api/products/{id}/versions", product.getId())
                        .file(file(pdf(2))).param("updatePolicy", "FREE_UPDATE_FOR_EXISTING")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PROCESSING"));

        DocumentVersion v2 = documentVersionRepository.findByProductIdAndVersionNumber(product.getId(), 2).orElseThrow();
        assertThat(v2.getUpdatePolicy()).isEqualTo(UpdatePolicy.FREE_UPDATE_FOR_EXISTING);

        // One version at a time: a second upload while v2 is QUEUED/PROCESSING is refused, and leaves no orphan row.
        int rawBefore = count("SELECT count(*) FROM document_versions WHERE product_id = ?", product.getId());
        mockMvc.perform(multipart("/api/products/{id}/versions", product.getId())
                        .file(file(pdf(1))).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest());
        assertThat(count("SELECT count(*) FROM document_versions WHERE product_id = ?", product.getId())).isEqualTo(rawBefore);
    }

    @Test
    void uploadVersion_rest_appliesTheSameValidationAsTheFirstUpload() throws Exception {
        Product product = productWithV1();
        String token = jwtService.generateAccessToken(creator);

        mockMvc.perform(multipart("/api/products/{id}/versions", product.getId())
                        .file(file(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'}))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("File is not a valid PDF. Only PDF files are accepted."));

        mockMvc.perform(multipart("/api/products/{id}/versions", product.getId())
                        .file(file(pdf(1))).param("updatePolicy", "WHATEVER")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest());

        assertThat(documentVersionRepository.findByProductIdOrderByVersionNumberDesc(product.getId())).hasSize(1);
    }

    @Test
    void uploadVersion_rest_isOwnerOnly() throws Exception {
        Product product = productWithV1();
        User rival = makeUser("rival@example.com", "Rival Creator", Role.BUYER, Role.CREATOR);

        mockMvc.perform(multipart("/api/products/{id}/versions", product.getId())
                        .file(file(pdf(2)))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.generateAccessToken(rival)))
                .andExpect(status().isForbidden());
        // A plain buyer (no CREATOR role) is turned away before the ownership check.
        mockMvc.perform(multipart("/api/products/{id}/versions", product.getId())
                        .file(file(pdf(2)))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.generateAccessToken(buyer)))
                .andExpect(status().isForbidden());
        // And anonymous callers get nothing.
        mockMvc.perform(multipart("/api/products/{id}/versions", product.getId()).file(file(pdf(2))))
                .andExpect(status().is4xxClientError());

        assertThat(documentVersionRepository.findByProductIdOrderByVersionNumberDesc(product.getId())).hasSize(1);
    }

    @Test
    void versionOperations_graphql_areOwnerOnly() {
        Product product = productWithV1();
        User rival = makeUser("rival2@example.com", "Rival Two", Role.BUYER, Role.CREATOR);
        HttpGraphQlTester asRival = tester(rival);
        Long v1 = versionId(product, 1);

        asRival.document(VERSIONS).variable("id", product.getId()).execute()
                .errors().expect(e -> "ACCESS_DENIED".equals(e.getExtensions().get("code"))).verify();
        asRival.document(RETIRE).variable("p", product.getId()).variable("v", v1).execute()
                .errors().expect(e -> "ACCESS_DENIED".equals(e.getExtensions().get("code"))).verify();
        asRival.document(RETRY).variable("p", product.getId()).variable("v", v1).execute()
                .errors().expect(e -> "ACCESS_DENIED".equals(e.getExtensions().get("code"))).verify();
    }

    @Test
    void uploadVersion_rest_isRefusedUntilTheFirstVersionExists() throws Exception {
        Category category = categoryRepository.findAll().get(0);
        Product draft = new Product();
        draft.setCreator(creator);
        draft.setCategory(category);
        draft.setTitle("Draft");
        draft.setSlug("draft-" + System.nanoTime());
        draft.setDescription("d");
        draft = productRepository.save(draft);

        mockMvc.perform(multipart("/api/products/{id}/versions", draft.getId())
                        .file(file(pdf(1)))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.generateAccessToken(creator)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void firstUploadEndpoint_refusesAProductThatAlreadyHasAVersion() throws Exception {
        Product product = productWithV1();

        mockMvc.perform(multipart("/api/products/{id}/document", product.getId())
                        .file(file(pdf(1)))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.generateAccessToken(creator)))
                .andExpect(status().isBadRequest());
    }
}
