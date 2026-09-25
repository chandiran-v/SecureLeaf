package com.secureleaf.content;

import com.secureleaf.AbstractIntegrationTest;
import com.secureleaf.auth.entity.User;
import com.secureleaf.auth.entity.UserRole;
import com.secureleaf.auth.repository.UserRepository;
import com.secureleaf.common.storage.InMemoryStorageService;
import com.secureleaf.content.entity.ContentPage;
import com.secureleaf.content.entity.DocumentVersion;
import com.secureleaf.content.repository.ContentPageRepository;
import com.secureleaf.content.repository.DocumentVersionRepository;
import com.secureleaf.content.service.DocumentProcessingService;
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

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end integration test for the 5-stage document processing pipeline.
 *
 * This test uses the {@code InMemoryStorageService} (automatically injected
 * via {@code @Primary} in the test context) so it runs fully isolated
 * without needing a real MinIO container.
 */
class ProcessingPipelineIT extends AbstractIntegrationTest {

    @Autowired
    private DocumentProcessingService pipeline;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private DocumentVersionRepository versionRepository;

    @Autowired
    private ProcessingJobRepository jobRepository;

    @Autowired
    private ContentPageRepository pageRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private InMemoryStorageService storage;

    @BeforeEach
    void setUp() {
        storage.clear();
        jobRepository.deleteAll();
        pageRepository.deleteAll();
        versionRepository.deleteAll();
        productRepository.deleteAll();
    }

    @Test
    void processAsync_success() throws Exception {
        // 1. Setup DB state
        User creator = new User();
        creator.setEmail("test@example.com");
        creator.setDisplayName("Test Creator");
        creator.setPasswordHash("hash");
        UserRole creatorRole = new UserRole();
        creatorRole.setUser(creator);
        creatorRole.setRole(com.secureleaf.auth.entity.Role.CREATOR);
        creator.getRoles().add(creatorRole);
        creator = userRepository.save(creator);

        Category cat = new Category();
        cat.setName("Tech");
        cat.setSlug("tech");
        cat = categoryRepository.save(cat);

        Product product = new Product();
        product.setCreator(creator);
        product.setCategory(cat);
        product.setTitle("Test PDF");
        product.setSlug("test-pdf");
        product.setDescription("Desc");
        product.setStatus(ProductStatus.PROCESSING);
        product = productRepository.save(product);

        DocumentVersion version = new DocumentVersion();
        version.setProduct(product);
        version.setOriginalFilename("test.pdf");
        version.setFileSizeBytes(1024L);
        version.setRawMinioBucket("raw-bucket");
        version.setRawMinioObjectKey("raw-key");
        version = versionRepository.save(version);

        ProcessingJob job = new ProcessingJob();
        job.setProduct(product);
        job.setDocumentVersion(version);
        job.setStatus(JobStatus.PROCESSING);
        job = jobRepository.save(job);

        // 2. Generate a valid 2-page PDF in memory
        byte[] pdfBytes;
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            doc.addPage(new PDPage());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            pdfBytes = out.toByteArray();
        }
        storage.put("raw-bucket", "raw-key", pdfBytes, "application/pdf");

        // 3. Run the pipeline. processAsync is @Async (contentProcessingExecutor), so this call
        // returns immediately — the assertions below must wait for the background thread, not
        // assume it already finished (that race is what made this test flaky/failing once IT
        // suites actually started running under Failsafe; see Phase 05A's PR).
        Long jobId = job.getId();
        pipeline.processAsync(jobId);

        // 4. Assert Job is COMPLETED
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(jobRepository.findById(jobId).orElseThrow().getStatus()).isEqualTo(JobStatus.COMPLETED));
        ProcessingJob finishedJob = jobRepository.findById(jobId).orElseThrow();
        assertThat(finishedJob.getCurrentStage()).isEqualTo(JobStage.MARK_LIVE);

        // 5. Assert Product is LIVE and has thumbnail
        Product finishedProduct = productRepository.findById(product.getId()).orElseThrow();
        assertThat(finishedProduct.getStatus()).isEqualTo(ProductStatus.LIVE);
        assertThat(finishedProduct.getCoverImageUrl()).isNotNull();

        // 6. Assert DocumentVersion has page count and thumbnail key
        DocumentVersion finishedVersion = versionRepository.findById(version.getId()).orElseThrow();
        assertThat(finishedVersion.getPageCount()).isEqualTo(2);
        assertThat(finishedVersion.getThumbnailMinioKey()).isNotNull();
        assertThat(storage.exists("secureleaf-thumbnails", finishedVersion.getThumbnailMinioKey())).isTrue();

        // 7. Assert 2 ContentPages were created and tiles exist in storage
        List<ContentPage> pages = pageRepository.findByDocumentVersionIdOrderByPageNumber(finishedVersion.getId());
        assertThat(pages).hasSize(2);
        assertThat(pages.get(0).getPageNumber()).isEqualTo(1);
        assertThat(pages.get(1).getPageNumber()).isEqualTo(2);

        assertThat(storage.exists(pages.get(0).getBucketName(), pages.get(0).getMinioObjectKey())).isTrue();
        assertThat(storage.exists(pages.get(1).getBucketName(), pages.get(1).getMinioObjectKey())).isTrue();
    }
}
