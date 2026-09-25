package com.secureleaf.creator;

import com.secureleaf.AbstractIntegrationTest;
import com.secureleaf.auth.entity.Role;
import com.secureleaf.auth.entity.User;
import com.secureleaf.auth.entity.UserRole;
import com.secureleaf.auth.repository.UserRepository;
import com.secureleaf.auth.service.JwtService;
import com.secureleaf.common.storage.InMemoryStorageService;
import com.secureleaf.content.entity.DocumentVersion;
import com.secureleaf.content.repository.DocumentVersionRepository;
import com.secureleaf.creator.entity.JobStage;
import com.secureleaf.creator.entity.JobStatus;
import com.secureleaf.creator.entity.ProcessingJob;
import com.secureleaf.creator.repository.ProcessingJobRepository;
import com.secureleaf.marketplace.entity.Category;
import com.secureleaf.marketplace.entity.Product;
import com.secureleaf.marketplace.entity.ProductStatus;
import com.secureleaf.marketplace.repository.CategoryRepository;
import com.secureleaf.marketplace.repository.ProductRepository;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Phase 6, D4 — {@code retryProcessing} (FAILED → QUEUED, reprocessed by the existing pipeline)
 * and {@code republishProduct} (UNPUBLISHED → LIVE). Both are owner-only, guarded state
 * transitions — see {@link com.secureleaf.creator.service.ProductRecoveryService}.
 */
class ProductRecoveryIT extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired private UserRepository userRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private DocumentVersionRepository documentVersionRepository;
    @Autowired private ProcessingJobRepository processingJobRepository;
    @Autowired private JwtService jwtService;
    @Autowired private InMemoryStorageService storage;

    private User creator;
    private User otherCreator;
    private Category category;

    @BeforeEach
    void setUp() {
        storage.clear();
        creator = makeCreator("creator@example.com", "Casey Creator");
        otherCreator = makeCreator("other-creator@example.com", "Olive Other");

        category = new Category();
        category.setName("Guides");
        category.setSlug("guides-" + System.nanoTime());
        category = categoryRepository.save(category);
    }

    // ── retryProcessing ──────────────────────────────────────────────────────

    @Test
    void retryProcessing_onLiveProduct_throwsInvalidStateTransition() {
        Product live = makeProduct(ProductStatus.LIVE);

        tester(creator).document("mutation($id: ID!) { retryProcessing(productId: $id) { status } }")
                .variable("id", live.getId())
                .execute()
                .errors().expect(e -> "INVALID_STATE_TRANSITION".equals(e.getExtensions().get("code"))).verify();
    }

    @Test
    void retryProcessing_byNonOwner_getsAccessDenied() {
        Product failed = makeProduct(ProductStatus.FAILED);

        tester(otherCreator).document("mutation($id: ID!) { retryProcessing(productId: $id) { status } }")
                .variable("id", failed.getId())
                .execute()
                .errors().expect(e -> "ACCESS_DENIED".equals(e.getExtensions().get("code"))).verify();
    }

    @Test
    void retryProcessing_onFailedProduct_resetsJob_andThePipelineReprocessesItToLive() throws Exception {
        Product product = makeProduct(ProductStatus.FAILED);

        byte[] pdfBytes = onePagePdf();
        String rawKey = "raw/" + product.getId() + ".pdf";
        storage.put("secureleaf-raw", rawKey, pdfBytes, "application/pdf");

        DocumentVersion version = new DocumentVersion();
        version.setProduct(product);
        version.setOriginalFilename("doc.pdf");
        version.setFileSizeBytes((long) pdfBytes.length);
        version.setRawMinioBucket("secureleaf-raw");
        version.setRawMinioObjectKey(rawKey);
        version = documentVersionRepository.save(version);

        ProcessingJob job = new ProcessingJob();
        job.setProduct(product);
        job.setDocumentVersion(version);
        job.setStatus(JobStatus.FAILED);
        job.setCurrentStage(JobStage.CONVERT_TILES);
        job.setRetryCount(3);
        job.setMaxRetries(3);
        job.setFailureReason("Simulated failure for the retry test");
        job = processingJobRepository.save(job);

        tester(creator).document("mutation($id: ID!) { retryProcessing(productId: $id) { status } }")
                .variable("id", product.getId())
                .execute()
                .path("retryProcessing.status").entity(String.class).isEqualTo("PROCESSING");

        ProcessingJob resetJob = processingJobRepository.findById(job.getId()).orElseThrow();
        assertThat(resetJob.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(resetJob.getRetryCount()).isZero();
        assertThat(resetJob.getFailureReason()).isNull();

        // No direct call into the pipeline here on purpose: the existing ProcessingJobWorker
        // (@Scheduled, every 5s) picks up the QUEUED job on its own, exactly like a brand-new
        // upload — retryProcessing's whole job is to put the row back where that poller looks.
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(productRepository.findById(product.getId()).orElseThrow().getStatus())
                        .isEqualTo(ProductStatus.LIVE));
    }

    // ── republishProduct ─────────────────────────────────────────────────────

    @Test
    void republishProduct_onUnpublishedProduct_makesItLiveAndVisibleInTheMarketplaceAgain() {
        Product product = makeProduct(ProductStatus.UNPUBLISHED);
        DocumentVersion version = new DocumentVersion();
        version.setProduct(product);
        version.setOriginalFilename("doc.pdf");
        version.setFileSizeBytes(10L);
        version.setRawMinioBucket("secureleaf-raw");
        version.setRawMinioObjectKey("raw/x.pdf");
        version.setPageCount(5);
        version.setProcessedAt(Instant.now());
        documentVersionRepository.save(version);

        tester(creator).document("mutation($id: ID!) { republishProduct(productId: $id) { status } }")
                .variable("id", product.getId())
                .execute()
                .path("republishProduct.status").entity(String.class).isEqualTo("LIVE");

        assertThat(productRepository.findById(product.getId()).orElseThrow().getStatus()).isEqualTo(ProductStatus.LIVE);

        tester(null).document("query($id: ID!) { product(id: $id) { id status } }")
                .variable("id", product.getId())
                .execute()
                .path("product.status").entity(String.class).isEqualTo("LIVE");
    }

    @Test
    void republishProduct_withNoProcessedVersion_throwsInvalidStateTransition() {
        Product product = makeProduct(ProductStatus.UNPUBLISHED);

        tester(creator).document("mutation($id: ID!) { republishProduct(productId: $id) { status } }")
                .variable("id", product.getId())
                .execute()
                .errors().expect(e -> "INVALID_STATE_TRANSITION".equals(e.getExtensions().get("code"))).verify();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private User makeCreator(String email, String name) {
        User user = new User();
        user.setEmail(email);
        user.setDisplayName(name);
        user.setPasswordHash("hash");
        UserRole role = new UserRole();
        role.setUser(user);
        role.setRole(Role.CREATOR);
        user.getRoles().add(role);
        return userRepository.save(user);
    }

    private Product makeProduct(ProductStatus status) {
        Product p = new Product();
        p.setCreator(creator);
        p.setCategory(category);
        p.setTitle("Test Product");
        p.setSlug("test-product-" + System.nanoTime());
        p.setDescription("A product used to test recovery mutations.");
        p.setPricePaise(1000L);
        p.setStatus(status);
        return productRepository.save(p);
    }

    /** Passing {@code null} builds an unauthenticated tester (for the public product query). */
    private HttpGraphQlTester tester(User user) {
        WebTestClient.Builder client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port + "/graphql");
        if (user != null) {
            client.defaultHeader("Authorization", "Bearer " + jwtService.generateAccessToken(user));
        }
        return HttpGraphQlTester.create(client.build());
    }

    private byte[] onePagePdf() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }
}
