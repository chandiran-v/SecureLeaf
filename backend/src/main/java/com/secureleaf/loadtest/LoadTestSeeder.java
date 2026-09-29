package com.secureleaf.loadtest;

import com.secureleaf.auth.entity.Role;
import com.secureleaf.auth.entity.User;
import com.secureleaf.auth.entity.UserRole;
import com.secureleaf.auth.repository.UserRepository;
import com.secureleaf.commerce.entity.Entitlement;
import com.secureleaf.commerce.entity.EntitlementStatus;
import com.secureleaf.commerce.entity.Order;
import com.secureleaf.commerce.entity.OrderStatus;
import com.secureleaf.commerce.repository.EntitlementRepository;
import com.secureleaf.commerce.repository.OrderRepository;
import com.secureleaf.common.config.MinioProperties;
import com.secureleaf.common.storage.StorageService;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Phase 10 D4 — creates the data a k6 run needs: N buyers, each with an ACTIVE entitlement for
 * one multi-page product built from a bundled PDF, and a CSV of their credentials.
 *
 * Runs only under the {@code loadtest} Spring profile and refuses to run when {@code prod} is
 * also active — it creates accounts with a known, published password.
 *
 * Idempotent: every row is looked up by a deterministic key (email, slug) before it is created,
 * so a second run creates nothing new and just rewrites the CSV.
 */
@Component
@Profile("loadtest")
@Slf4j
public class LoadTestSeeder implements ApplicationRunner {

    static final String CREATOR_EMAIL = "loadtest-creator@secureleaf.test";
    static final String PRODUCT_SLUG = "loadtest-multi-page-book";
    static final String CATEGORY_SLUG = "loadtest";
    static final String PDF_RESOURCE = "loadtest/loadtest-book.pdf";

    private final Environment environment;
    private final UserRepository userRepository;
    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;
    private final DocumentVersionRepository documentVersionRepository;
    private final ProcessingJobRepository processingJobRepository;
    private final DocumentProcessingService documentProcessingService;
    private final OrderRepository orderRepository;
    private final EntitlementRepository entitlementRepository;
    private final StorageService storageService;
    private final MinioProperties minioProperties;
    private final PasswordEncoder passwordEncoder;

    private final int userCount;
    private final String password;
    private final Path outputCsv;
    private final Duration processingTimeout;

    public LoadTestSeeder(Environment environment,
                          UserRepository userRepository,
                          CategoryRepository categoryRepository,
                          ProductRepository productRepository,
                          DocumentVersionRepository documentVersionRepository,
                          ProcessingJobRepository processingJobRepository,
                          DocumentProcessingService documentProcessingService,
                          OrderRepository orderRepository,
                          EntitlementRepository entitlementRepository,
                          StorageService storageService,
                          MinioProperties minioProperties,
                          PasswordEncoder passwordEncoder,
                          @Value("${loadtest.users:500}") int userCount,
                          @Value("${loadtest.password:LoadTest#2026}") String password,
                          @Value("${loadtest.output:loadtest/users.csv}") String outputCsv,
                          @Value("${loadtest.processing-timeout-seconds:180}") long processingTimeoutSeconds) {
        this.environment = environment;
        this.userRepository = userRepository;
        this.categoryRepository = categoryRepository;
        this.productRepository = productRepository;
        this.documentVersionRepository = documentVersionRepository;
        this.processingJobRepository = processingJobRepository;
        this.documentProcessingService = documentProcessingService;
        this.orderRepository = orderRepository;
        this.entitlementRepository = entitlementRepository;
        this.storageService = storageService;
        this.minioProperties = minioProperties;
        this.passwordEncoder = passwordEncoder;
        this.userCount = userCount;
        this.password = password;
        this.outputCsv = Path.of(outputCsv);
        this.processingTimeout = Duration.ofSeconds(processingTimeoutSeconds);
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        seed();
    }

    /** @return how many buyer accounts this call created (0 on a repeat run). */
    public int seed() throws InterruptedException {
        // The whole point of the guard: never plant known-password accounts in production.
        if (environment.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException("LoadTestSeeder refuses to run with the 'prod' profile active.");
        }
        if (userCount < 1) {
            throw new IllegalArgumentException("loadtest.users must be at least 1");
        }

        // One BCrypt hash shared by every account: hashing is deliberately slow (~100 ms), and 500
        // separate hashes would make seeding take a minute for no benefit — same password anyway.
        String passwordHash = passwordEncoder.encode(password);

        User creator = userRepository.findByEmail(CREATOR_EMAIL)
                .orElseGet(() -> createUser(CREATOR_EMAIL, "Load Test Creator", passwordHash, Role.BUYER, Role.CREATOR));
        Product product = productRepository.findAll().stream()
                .filter(p -> PRODUCT_SLUG.equals(p.getSlug())).findFirst()
                .orElseGet(() -> createProduct(creator));
        DocumentVersion version = waitUntilProcessed(product);

        List<String> buyerEmails = new ArrayList<>();
        int created = 0;
        for (int i = 1; i <= userCount; i++) {
            String email = buyerEmail(i);
            buyerEmails.add(email);
            User buyer = userRepository.findByEmail(email).orElse(null);
            if (buyer == null) {
                buyer = createUser(email, "Load Test Buyer " + i, passwordHash, Role.BUYER);
                created++;
            }
            if (!entitlementRepository.existsByBuyerIdAndProductIdAndStatus(
                    buyer.getId(), product.getId(), EntitlementStatus.ACTIVE)) {
                grantEntitlement(buyer, product, version);
            }
        }

        writeCsv(buyerEmails, product.getId());
        log.info("Load-test seed done: {} buyers ({} new), product {} ({} pages), credentials in {}",
                userCount, created, product.getId(), version.getPageCount(), outputCsv.toAbsolutePath());
        return created;
    }

    static String buyerEmail(int index) {
        return "loadtest-buyer-%04d@secureleaf.test".formatted(index);
    }

    private User createUser(String email, String displayName, String passwordHash, Role... roles) {
        User user = new User();
        user.setEmail(email);
        user.setDisplayName(displayName);
        user.setPasswordHash(passwordHash);
        for (Role role : roles) {
            UserRole userRole = new UserRole();
            userRole.setUser(user);
            userRole.setRole(role);
            user.getRoles().add(userRole);
        }
        return userRepository.save(user);
    }

    /** The upload controller's steps (raw PDF → version row → job), minus the HTTP part. The job is
     *  created already claimed (PROCESSING) and handed straight to the real pipeline, so no other
     *  worker can pick it up and the tiles are rendered exactly as for a real upload. */
    private Product createProduct(User creator) {
        Category category = categoryRepository.findBySlug(CATEGORY_SLUG).orElseGet(() -> {
            Category c = new Category();
            c.setName("Load test");
            c.setSlug(CATEGORY_SLUG);
            return categoryRepository.save(c);
        });

        Product product = new Product();
        product.setCreator(creator);
        product.setCategory(category);
        product.setTitle("Load-test multi-page book");
        product.setSlug(PRODUCT_SLUG);
        product.setDescription("Seeded by LoadTestSeeder for k6 runs. Not real content.");
        product.setPricePaise(0L);
        product.setFreePreviewPages(1);
        product.setStatus(ProductStatus.PROCESSING);
        product = productRepository.save(product);

        byte[] pdf = readBundledPdf();
        String rawBucket = minioProperties.getBucket().getRawUploads();
        String objectKey = "products/%d/v1/%s.pdf".formatted(product.getId(), UUID.randomUUID());
        storageService.put(rawBucket, objectKey, pdf, "application/pdf");

        DocumentVersion version = new DocumentVersion();
        version.setProduct(product);
        version.setVersionNumber(1);
        version.setOriginalFilename("loadtest-book.pdf");
        version.setFileSizeBytes((long) pdf.length);
        version.setRawMinioBucket(rawBucket);
        version.setRawMinioObjectKey(objectKey);
        version = documentVersionRepository.save(version);

        ProcessingJob job = new ProcessingJob();
        job.setDocumentVersion(version);
        job.setProduct(product);
        job.setStatus(JobStatus.PROCESSING);
        job.setWorkerId("loadtest-seeder");
        job.setClaimedAt(Instant.now());
        job = processingJobRepository.save(job);
        documentProcessingService.processAsync(job.getId());   // @Async: returns at once
        return product;
    }

    private DocumentVersion waitUntilProcessed(Product product) throws InterruptedException {
        long deadline = System.nanoTime() + processingTimeout.toNanos();
        while (true) {
            DocumentVersion version = documentVersionRepository
                    .findByProductIdAndVersionNumber(product.getId(), 1).orElseThrow();
            if (version.getPageCount() != null && version.getPageCount() > 0) {
                return version;
            }
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("Load-test product " + product.getId()
                        + " was not processed within " + processingTimeout.toSeconds()
                        + "s. Check the backend log for a [job-N] failure.");
            }
            Thread.sleep(1000);
        }
    }

    private void grantEntitlement(User buyer, Product product, DocumentVersion version) {
        Order order = new Order();
        order.setBuyer(buyer);
        order.setTotalAmountPaise(0L);
        order.transitionTo(OrderStatus.COMPLETED);
        order = orderRepository.save(order);

        Entitlement entitlement = new Entitlement();
        entitlement.setBuyer(buyer);
        entitlement.setProduct(product);
        entitlement.setDocumentVersion(version);
        entitlement.setOrder(order);
        entitlement.setStatus(EntitlementStatus.ACTIVE);
        entitlementRepository.save(entitlement);
    }

    private void writeCsv(List<String> emails, Long productId) {
        StringBuilder csv = new StringBuilder("email,password,productId\n");
        for (String email : emails) {
            csv.append(email).append(',').append(password).append(',').append(productId).append('\n');
        }
        try {
            Path parent = outputCsv.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(outputCsv, csv, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + outputCsv, e);
        }
    }

    private static byte[] readBundledPdf() {
        try (InputStream in = new ClassPathResource(PDF_RESOURCE).getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Missing bundled " + PDF_RESOURCE, e);
        }
    }
}
