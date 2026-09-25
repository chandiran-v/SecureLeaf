package com.secureleaf.content;

import com.secureleaf.AbstractIntegrationTest;
import com.secureleaf.auth.entity.Role;
import com.secureleaf.auth.entity.User;
import com.secureleaf.auth.entity.UserRole;
import com.secureleaf.auth.repository.UserRepository;
import com.secureleaf.common.storage.InMemoryStorageService;
import com.secureleaf.content.entity.DocumentVersion;
import com.secureleaf.content.repository.DocumentVersionRepository;
import com.secureleaf.content.service.DocumentProcessingService;
import com.secureleaf.creator.entity.JobStatus;
import com.secureleaf.creator.entity.ProcessingJob;
import com.secureleaf.creator.repository.ProcessingJobRepository;
import com.secureleaf.marketplace.entity.Category;
import com.secureleaf.marketplace.entity.Product;
import com.secureleaf.marketplace.entity.ProductStatus;
import com.secureleaf.marketplace.repository.CategoryRepository;
import com.secureleaf.marketplace.repository.ProductRepository;
import com.secureleaf.notification.RecordingNotificationPublisher;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayOutputStream;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Phase 6, D6 (NOTIF-03) — the pipeline notifies the creator when it finishes (LIVE) or gives up
 * after exhausting its retries (FAILED). Both go through {@code NotificationService}'s
 * AFTER_COMMIT path, same as PAY-08's purchase notifications.
 */
class ProcessingNotificationIT extends AbstractIntegrationTest {

    @Autowired private DocumentProcessingService pipeline;
    @Autowired private ProductRepository productRepository;
    @Autowired private DocumentVersionRepository versionRepository;
    @Autowired private ProcessingJobRepository jobRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private InMemoryStorageService storage;
    @Autowired private RecordingNotificationPublisher notificationPublisher;
    @Autowired private JdbcTemplate jdbcTemplate;

    private User creator;
    private Category category;

    @BeforeEach
    void setUp() {
        storage.clear();
        notificationPublisher.clear();

        creator = new User();
        creator.setEmail("creator@example.com");
        creator.setDisplayName("Casey Creator");
        creator.setPasswordHash("hash");
        UserRole role = new UserRole();
        role.setUser(creator);
        role.setRole(Role.CREATOR);
        creator.getRoles().add(role);
        creator = userRepository.save(creator);

        category = new Category();
        category.setName("Tech");
        category.setSlug("tech-" + System.nanoTime());
        category = categoryRepository.save(category);
    }

    @Test
    void completedPipeline_createsAProcessingCompleteNotification() throws Exception {
        Product product = makeProduct();

        byte[] pdfBytes = onePagePdf();
        storage.put("raw-bucket", "raw-key", pdfBytes, "application/pdf");

        DocumentVersion version = new DocumentVersion();
        version.setProduct(product);
        version.setOriginalFilename("doc.pdf");
        version.setFileSizeBytes((long) pdfBytes.length);
        version.setRawMinioBucket("raw-bucket");
        version.setRawMinioObjectKey("raw-key");
        version = versionRepository.save(version);

        ProcessingJob job = new ProcessingJob();
        job.setProduct(product);
        job.setDocumentVersion(version);
        job.setStatus(JobStatus.PROCESSING);
        job = jobRepository.save(job);

        pipeline.processAsync(job.getId());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(notificationTypesFor(creator.getId())).contains("PROCESSING_COMPLETE"));
    }

    @Test
    void exhaustedRetries_createAProcessingFailedNotification() {
        Product product = makeProduct();

        // No storage.put() for this key — DocumentProcessingService.runPipeline's very first
        // read (storageService.get) throws, which is all this test needs to force a failure.
        DocumentVersion version = new DocumentVersion();
        version.setProduct(product);
        version.setOriginalFilename("doc.pdf");
        version.setFileSizeBytes(10L);
        version.setRawMinioBucket("raw-bucket");
        version.setRawMinioObjectKey("missing-key");
        version = versionRepository.save(version);

        ProcessingJob job = new ProcessingJob();
        job.setProduct(product);
        job.setDocumentVersion(version);
        job.setStatus(JobStatus.PROCESSING);
        job.setRetryCount(2);
        job.setMaxRetries(3);   // this failure is the 3rd attempt — retries are exhausted
        job = jobRepository.save(job);
        Long jobId = job.getId();
        Long productId = product.getId();

        pipeline.processAsync(jobId);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(jobRepository.findById(jobId).orElseThrow().getStatus()).isEqualTo(JobStatus.FAILED);
            assertThat(productRepository.findById(productId).orElseThrow().getStatus()).isEqualTo(ProductStatus.FAILED);
            assertThat(notificationTypesFor(creator.getId())).contains("PROCESSING_FAILED");
        });
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Product makeProduct() {
        Product product = new Product();
        product.setCreator(creator);
        product.setCategory(category);
        product.setTitle("Test PDF " + System.nanoTime());
        product.setSlug("test-pdf-" + System.nanoTime());
        product.setDescription("Desc");
        product.setStatus(ProductStatus.PROCESSING);
        return productRepository.save(product);
    }

    private java.util.List<String> notificationTypesFor(Long recipientId) {
        return jdbcTemplate.queryForList(
                "SELECT type::text FROM notifications WHERE recipient_id = ?", String.class, recipientId);
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
