package com.secureleaf.viewer;

import com.secureleaf.AbstractIntegrationTest;
import com.secureleaf.auth.entity.Role;
import com.secureleaf.auth.entity.User;
import com.secureleaf.auth.entity.UserRole;
import com.secureleaf.auth.repository.UserRepository;
import com.secureleaf.auth.service.JwtService;
import com.secureleaf.commerce.entity.Entitlement;
import com.secureleaf.commerce.entity.EntitlementStatus;
import com.secureleaf.commerce.entity.Order;
import com.secureleaf.commerce.entity.OrderStatus;
import com.secureleaf.commerce.repository.EntitlementRepository;
import com.secureleaf.commerce.repository.OrderRepository;
import com.secureleaf.common.storage.InMemoryStorageService;
import com.secureleaf.content.entity.ContentPage;
import com.secureleaf.content.entity.DocumentVersion;
import com.secureleaf.content.repository.ContentPageRepository;
import com.secureleaf.content.repository.DocumentVersionRepository;
import com.secureleaf.content.service.DocumentProcessingService;
import com.secureleaf.content.tiles.VariantBackfillService;
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
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Phase 16 acceptance criteria 1-4 (docs/phases/phase-16-adaptive-tile-resolution.md). The document
 * is produced by the REAL pipeline from a real 2-page PDF, so the stored variants are the ones
 * production would create.
 */
class AdaptiveTileVariantsIT extends AbstractIntegrationTest {

    private static final int LETTER_WIDTH_AT_150_DPI = 1275;   // 612 pt * 150 / 72
    private static final int MOBILE_WIDTH = 900;

    @LocalServerPort private int port;

    @Autowired private UserRepository userRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private DocumentVersionRepository documentVersionRepository;
    @Autowired private ContentPageRepository contentPageRepository;
    @Autowired private ProcessingJobRepository jobRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private EntitlementRepository entitlementRepository;
    @Autowired private DocumentProcessingService pipeline;
    @Autowired private VariantBackfillService backfill;
    @Autowired private InMemoryStorageService storage;
    @Autowired private JwtService jwtService;
    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;

    private User buyer;
    private User admin;
    private Product product;
    private DocumentVersion version;
    private HttpGraphQlTester asBuyer;
    private HttpGraphQlTester asAdmin;

    @BeforeEach
    void processARealPdf() throws Exception {
        storage.clear();
        User creator = makeUser("creator@variants.test", Role.BUYER, Role.CREATOR);
        buyer = makeUser("buyer@variants.test", Role.BUYER);
        admin = makeUser("admin@variants.test", Role.BUYER, Role.ADMIN);

        Category category = new Category();
        category.setName("Guides");
        category.setSlug("guides-" + System.nanoTime());
        category = categoryRepository.save(category);

        product = new Product();
        product.setCreator(creator);
        product.setCategory(category);
        product.setTitle("Variant Book");
        product.setSlug("variant-book-" + System.nanoTime());
        product.setDescription("desc");
        product.setPricePaise(0L);
        product.setStatus(ProductStatus.PROCESSING);
        product.setFreePreviewPages(1);
        product = productRepository.save(product);

        version = new DocumentVersion();
        version.setProduct(product);
        version.setVersionNumber(1);
        version.setOriginalFilename("book.pdf");
        version.setFileSizeBytes(2048L);
        version.setRawMinioBucket("secureleaf-raw");
        version.setRawMinioObjectKey("raw/variants.pdf");
        version = documentVersionRepository.save(version);
        storage.put("secureleaf-raw", "raw/variants.pdf", twoPagePdf(), "application/pdf");

        ProcessingJob job = new ProcessingJob();
        job.setProduct(product);
        job.setDocumentVersion(version);
        job.setStatus(JobStatus.PROCESSING);
        job = jobRepository.save(job);
        Long jobId = job.getId();
        pipeline.processAsync(jobId);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(jobRepository.findById(jobId).orElseThrow().getStatus()).isEqualTo(JobStatus.COMPLETED));
        version = documentVersionRepository.findById(version.getId()).orElseThrow();

        Order order = new Order();
        order.setBuyer(buyer);
        order.setTotalAmountPaise(0L);
        order.transitionTo(OrderStatus.COMPLETED);
        order = orderRepository.save(order);
        Entitlement e = new Entitlement();
        e.setBuyer(buyer);
        e.setProduct(product);
        e.setDocumentVersion(version);
        e.setOrder(order);
        e.setStatus(EntitlementStatus.ACTIVE);
        entitlementRepository.save(e);

        asBuyer = tester(buyer);
        asAdmin = tester(admin);
    }

    // ═══ 1. A new upload produces both variants for every page, at the expected widths ═══
    @Test
    void pipeline_rendersEveryVariantOfEveryPage_atTheExpectedWidths() {
        List<ContentPage> pages = contentPageRepository.findByDocumentVersionIdOrderByPageNumber(version.getId());

        assertThat(pages).hasSize(4);
        for (ContentPage page : pages) {
            int expected = page.getVariant().equals("MOBILE") ? MOBILE_WIDTH : LETTER_WIDTH_AT_150_DPI;
            assertThat(page.getWidthPx()).as("%s page %d", page.getVariant(), page.getPageNumber()).isEqualTo(expected);
            assertThat(page.getMinioObjectKey()).contains("/" + page.getVariant() + "/");
            assertThat(storage.exists(page.getBucketName(), page.getMinioObjectKey())).isTrue();
        }
        // Aspect ratio is kept: 792/612 of the width, to within a pixel of rounding.
        ContentPage mobile = pageOf("MOBILE", 1);
        assertThat((double) mobile.getHeightPx() / mobile.getWidthPx()).isCloseTo(792.0 / 612.0,
                org.assertj.core.data.Offset.offset(0.005));
        assertThat(pageOf("MOBILE", 1).getFileSizeBytes()).isLessThan(pageOf("DESKTOP", 1).getFileSizeBytes());
    }

    // ═══ 2. MOBILE returns the smaller image, watermarked; a tampered variant is 403 ═══
    @Test
    void mobileVariant_isSmaller_watermarked_andLoggedWithItsVariant() throws Exception {
        String url = pageUrl(sessionToken(), 1, "MOBILE");
        assertThat(url).endsWith("&v=MOBILE");

        byte[] bytes = fetch(url).getResponse().getContentAsByteArray();
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        assertThat(image.getWidth()).isEqualTo(MOBILE_WIDTH);
        assertThat(bytes).isNotEqualTo(storage.get(pageOf("MOBILE", 1).getBucketName(), pageOf("MOBILE", 1).getMinioObjectKey()));
        assertThat(jdbc.queryForObject("SELECT variant FROM viewer_access_logs", String.class)).isEqualTo("MOBILE");
    }

    @Test
    void desktopIsTheDefault_andServesTheFullWidth() throws Exception {
        String url = pageUrl(sessionToken(), 1, null);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(fetch(url).getResponse().getContentAsByteArray()));
        assertThat(image.getWidth()).isEqualTo(LETTER_WIDTH_AT_150_DPI);
        assertThat(jdbc.queryForObject("SELECT variant FROM viewer_access_logs", String.class)).isEqualTo("DESKTOP");
    }

    @Test
    void tamperedVariant_returns403_andNothingIsLogged() throws Exception {
        String mobileUrl = pageUrl(sessionToken(), 1, "MOBILE");
        assertThat(fetch(mobileUrl.replace("&v=MOBILE", "&v=DESKTOP")).getResponse().getStatus()).isEqualTo(403);

        String again = pageUrl(sessionToken(), 1, "MOBILE");
        assertThat(fetch(again.replace("&v=MOBILE", "")).getResponse().getStatus()).isEqualTo(403);   // v dropped = DESKTOP

        String bogus = pageUrl(sessionToken(), 1, "MOBILE");
        assertThat(fetch(bogus.replace("&v=MOBILE", "&v=HUGE")).getResponse().getStatus()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM viewer_access_logs", Integer.class)).isZero();
    }

    @Test
    void unknownVariantRequestedFromGraphQl_isSignedAsDesktop() {
        assertThat(pageUrl(sessionToken(), 1, "HOLOGRAM")).endsWith("&v=DESKTOP");
    }

    // ═══ 3. A missing MOBILE variant falls back to DESKTOP with no error ═══
    @Test
    void missingMobileVariant_fallsBackToDesktop() throws Exception {
        jdbc.update("DELETE FROM content_pages WHERE variant = 'MOBILE'");

        String url = pageUrl(sessionToken(), 1, "MOBILE");
        MvcResult result = fetch(url);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(ImageIO.read(new ByteArrayInputStream(result.getResponse().getContentAsByteArray())).getWidth())
                .isEqualTo(LETTER_WIDTH_AT_150_DPI);
        assertThat(jdbc.queryForObject("SELECT variant FROM viewer_access_logs", String.class))
                .as("the log records what was SERVED, not what was asked for").isEqualTo("DESKTOP");
    }

    // ═══ 4. Backfill: renders what is missing, idempotent, resumable, admin-only ═══
    @Test
    void backfill_rendersMissingVariants_isIdempotent_andResumesAtPageLevel() {
        jdbc.update("DELETE FROM content_pages WHERE variant = 'MOBILE'");
        assertThat(mobileRows()).isZero();

        assertThat(backfill.runAll()).isEqualTo(2);
        assertThat(mobileRows()).isEqualTo(2);
        assertThat(pageOf("MOBILE", 2).getWidthPx()).isEqualTo(MOBILE_WIDTH);
        assertThat(storage.exists(pageOf("MOBILE", 2).getBucketName(), pageOf("MOBILE", 2).getMinioObjectKey())).isTrue();
        assertThat(backfill.runAll()).as("second run has nothing to do").isZero();
        assertThat(mobileRows()).isEqualTo(2);

        // "Crashed" half-way: only page 2 is missing, so only page 2 is rendered.
        jdbc.update("DELETE FROM content_pages WHERE variant = 'MOBILE' AND page_number = 2");
        assertThat(backfill.runAll()).isEqualTo(1);
        assertThat(mobileRows()).isEqualTo(2);
    }

    @Test
    void backfill_skipsRetiredVersions() {
        jdbc.update("DELETE FROM content_pages WHERE variant = 'MOBILE'");
        jdbc.update("UPDATE document_versions SET retired_at = now() WHERE id = ?", version.getId());

        assertThat(backfill.runAll()).isZero();
        assertThat(mobileRows()).isZero();
    }

    private static final String GENERATE = "mutation { generateMissingVariants }";

    @Test
    void generateMissingVariants_isAdminOnly_andRunsInTheBackground() {
        asBuyer.document(GENERATE).execute().errors().expect(e -> true).verify();
        assertThat(mobileRows()).isEqualTo(2);

        jdbc.update("DELETE FROM content_pages WHERE variant = 'MOBILE'");
        asAdmin.document(GENERATE).execute().path("generateMissingVariants").entity(Boolean.class).isEqualTo(true);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(backfill.isRunning()).isFalse();
            assertThat(mobileRows()).isEqualTo(2);
        });
    }

    // ═══ Helpers ═════════════════════════════════════════════════════════════

    private int mobileRows() {
        return jdbc.queryForObject("SELECT count(*) FROM content_pages WHERE variant = 'MOBILE'", Integer.class);
    }

    private ContentPage pageOf(String variant, int pageNumber) {
        return contentPageRepository.findByDocumentVersionIdAndPageNumberAndVariant(version.getId(), pageNumber, variant)
                .orElseThrow();
    }

    private String sessionToken() {
        return asBuyer.document("""
                mutation($productId: ID!, $fp: String!) {
                  startViewerSession(productId: $productId, deviceFingerprint: $fp) { sessionToken }
                }
                """).variable("productId", product.getId()).variable("fp", "device")
                .execute().path("startViewerSession.sessionToken").entity(String.class).get();
    }

    private String pageUrl(String token, int page, String variant) {
        String query = variant == null
                ? "query($t: String!, $p: Int!) { viewerPageUrl(sessionToken: $t, pageNumber: $p) { url } }"
                : "query($t: String!, $p: Int!, $v: String) { viewerPageUrl(sessionToken: $t, pageNumber: $p, variant: $v) { url } }";
        HttpGraphQlTester.Request<?> request = asBuyer.document(query).variable("t", token).variable("p", page);
        if (variant != null) request = request.variable("v", variant);
        return request.execute().path("viewerPageUrl.url").entity(String.class).get();
    }

    private MvcResult fetch(String url) throws Exception {
        MvcResult started = mockMvc.perform(get(url).header("Authorization", "Bearer " + jwtService.generateAccessToken(buyer)))
                .andReturn();
        return started.getRequest().isAsyncStarted() ? mockMvc.perform(asyncDispatch(started)).andReturn() : started;
    }

    private User makeUser(String email, Role... roles) {
        User user = new User();
        user.setEmail(email);
        user.setDisplayName(email);
        user.setPasswordHash("hash");
        for (Role role : roles) {
            UserRole userRole = new UserRole();
            userRole.setUser(user);
            userRole.setRole(role);
            user.getRoles().add(userRole);
        }
        return userRepository.save(user);
    }

    private HttpGraphQlTester tester(User user) {
        WebTestClient.Builder client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port + "/graphql");
        client.defaultHeader("Authorization", "Bearer " + jwtService.generateAccessToken(user));
        return HttpGraphQlTester.create(client.build());
    }

    /** A Letter-size (612 x 792 pt) PDF with some text on each page. */
    private static byte[] twoPagePdf() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 1; i <= 2; i++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 18);
                    content.newLineAtOffset(72, 700);
                    content.showText("Variant test page " + i);
                    content.endText();
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }
}
