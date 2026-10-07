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
import com.secureleaf.marketplace.entity.Category;
import com.secureleaf.marketplace.entity.Product;
import com.secureleaf.marketplace.entity.ProductStatus;
import com.secureleaf.marketplace.repository.CategoryRepository;
import com.secureleaf.marketplace.repository.ProductRepository;
import com.secureleaf.viewer.entity.BuyerPage;
import com.secureleaf.viewer.metrics.ViewerMetrics;
import com.secureleaf.viewer.prewatermark.BuyerPageReconciler;
import com.secureleaf.viewer.repository.BuyerPageRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Purchase-time watermarking: the reconciler gives every ACTIVE entitlement its own stored,
 * watermarked pages; the view path serves them without rendering; refunds and version moves clean
 * them up; and a page that does not exist yet still works (live watermark). The timer is off in
 * tests, so each test drives {@link BuyerPageReconciler#runOnce()} itself.
 */
class BuyerPageReconcilerIT extends AbstractIntegrationTest {

    private static final int PAGES = 3;

    @LocalServerPort private int port;
    @Autowired private UserRepository userRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private DocumentVersionRepository documentVersionRepository;
    @Autowired private ContentPageRepository contentPageRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private EntitlementRepository entitlementRepository;
    @Autowired private BuyerPageRepository buyerPageRepository;
    @Autowired private BuyerPageReconciler reconciler;
    @Autowired private InMemoryStorageService storage;
    @Autowired private JwtService jwtService;
    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MeterRegistry meterRegistry;

    private User buyer;
    private Product product;
    private DocumentVersion v1;
    private Entitlement entitlement;

    @BeforeEach
    void setUp() {
        storage.clear();
        User creator = makeUser("creator@prewm.test", Role.BUYER, Role.CREATOR);
        buyer = makeUser("buyer@prewm.test", Role.BUYER);

        Category category = new Category();
        category.setName("Prewatermark");
        category.setSlug("prewm-" + System.nanoTime());
        category = categoryRepository.save(category);

        product = new Product();
        product.setCreator(creator);
        product.setCategory(category);
        product.setTitle("Pre-watermark Book");
        product.setSlug("prewm-book-" + System.nanoTime());
        product.setDescription("desc");
        product.setPricePaise(0L);
        product.setStatus(ProductStatus.LIVE);
        product.setFreePreviewPages(1);
        product = productRepository.save(product);

        v1 = processedVersion(1, PAGES);
        product.setCurrentDocumentVersion(v1);
        product = productRepository.save(product);
        entitlement = grantEntitlement(buyer, v1);
    }

    @Test
    void runOnce_generatesEveryPageOfAnActiveEntitlement_andIsIdempotent() {
        BuyerPageReconciler.Result first = reconciler.runOnce();

        assertThat(first.pagesGenerated()).isEqualTo(PAGES);
        List<BuyerPage> rows = buyerPageRepository.findAll();
        assertThat(rows).hasSize(PAGES).allSatisfy(row -> {
            assertThat(row.getEntitlementId()).isEqualTo(entitlement.getId());
            assertThat(row.getDocumentVersionId()).isEqualTo(v1.getId());
            byte[] stored = storage.get(row.getBucketName(), row.getObjectKey());
            assertThat(stored).isNotEqualTo(cleanPagePng());   // watermarked, never the clean tile
        });

        assertThat(reconciler.runOnce().pagesGenerated()).isZero();   // nothing missing any more
        assertThat(buyerPageRepository.count()).isEqualTo(PAGES);
    }

    @Test
    void twoBuyers_getDifferentStoredPages() {
        User second = makeUser("second@prewm.test", Role.BUYER);
        Entitlement secondEntitlement = grantEntitlement(second, v1);

        reconciler.runOnce();

        byte[] mine = storedPage(entitlement, 1);
        byte[] theirs = storedPage(secondEntitlement, 1);
        assertThat(mine).isNotEqualTo(theirs);   // each copy carries its own buyer's watermark
    }

    @Test
    void pageView_servesTheStoredPage_withoutRenderingAndStillLogsTheAccess() throws Exception {
        reconciler.runOnce();
        long rendersBefore = watermarkRenders();
        double hitsBefore = counter(ViewerMetrics.PREWATERMARK_HIT);

        byte[] served = fetchTile(1);

        assertThat(served).isEqualTo(storedPage(entitlement, 1));   // byte-for-byte the stored copy
        assertThat(watermarkRenders()).isEqualTo(rendersBefore);     // no live render happened
        assertThat(counter(ViewerMetrics.PREWATERMARK_HIT)).isEqualTo(hitsBefore + 1);
        assertThat(accessLogCount()).isEqualTo(1);                   // VIEW-13: every view is logged
    }

    @Test
    void pageView_beforeGeneration_fallsBackToLiveWatermarking() throws Exception {
        long rendersBefore = watermarkRenders();

        byte[] served = fetchTile(1);

        assertThat(served).isNotEqualTo(cleanPagePng());
        assertThat(watermarkRenders()).isEqualTo(rendersBefore + 1);   // rendered live, as before
        assertThat(buyerPageRepository.count()).isZero();
    }

    @Test
    void refundedEntitlement_hasItsPagesAndObjectsDeleted() {
        reconciler.runOnce();
        List<BuyerPage> before = buyerPageRepository.findAll();

        entitlement.setStatus(EntitlementStatus.REVOKED);
        entitlement.setRevokedAt(Instant.now());
        entitlementRepository.save(entitlement);
        BuyerPageReconciler.Result result = reconciler.runOnce();

        assertThat(result.pagesDeleted()).isEqualTo(PAGES);
        assertThat(result.pagesGenerated()).isZero();   // a revoked entitlement gets nothing new
        assertThat(buyerPageRepository.count()).isZero();
        assertThat(before).allSatisfy(row ->
                assertThat(storage.exists(row.getBucketName(), row.getObjectKey())).isFalse());
    }

    @Test
    void entitlementMovedToANewVersion_getsTheNewPages_andLosesTheOldOnes() {
        reconciler.runOnce();
        DocumentVersion v2 = processedVersion(2, PAGES + 2);
        jdbcTemplate.update("UPDATE entitlements SET document_version_id = ? WHERE id = ?", v2.getId(), entitlement.getId());

        BuyerPageReconciler.Result result = reconciler.runOnce();

        assertThat(result.pagesDeleted()).isEqualTo(PAGES);
        assertThat(result.pagesGenerated()).isEqualTo(PAGES + 2);
        assertThat(buyerPageRepository.findAll()).allSatisfy(row ->
                assertThat(row.getDocumentVersionId()).isEqualTo(v2.getId()));
    }

    @Test
    void versionStillProcessing_isSkipped() {
        DocumentVersion processing = processedVersion(2, PAGES);
        processing.setProcessedAt(null);
        documentVersionRepository.save(processing);
        User other = makeUser("waiting@prewm.test", Role.BUYER);
        grantEntitlement(other, processing);

        reconciler.runOnce();

        assertThat(buyerPageRepository.findAll())
                .extracting(BuyerPage::getDocumentVersionId).containsOnly(v1.getId());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private byte[] fetchTile(int page) throws Exception {
        String token = jwtService.generateAccessToken(buyer);
        HttpGraphQlTester asBuyer = HttpGraphQlTester.create(WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port + "/graphql")
                .defaultHeader("Authorization", "Bearer " + token).build());
        @SuppressWarnings("unchecked")
        Map<String, Object> session = asBuyer.document("""
                        mutation($productId: ID!) {
                          startViewerSession(productId: $productId, deviceFingerprint: "device-1") { sessionToken }
                        }""")
                .variable("productId", product.getId())
                .execute().path("startViewerSession").entity(Map.class).get();
        String url = asBuyer.document("""
                        query($token: String!, $page: Int!) { viewerPageUrl(sessionToken: $token, pageNumber: $page) { url } }""")
                .variable("token", session.get("sessionToken")).variable("page", page)
                .execute().path("viewerPageUrl.url").entity(String.class).get();

        MvcResult started = mockMvc.perform(get(url).header("Authorization", "Bearer " + token)).andReturn();
        return mockMvc.perform(asyncDispatch(started)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
    }

    private byte[] storedPage(Entitlement e, int page) {
        BuyerPage row = buyerPageRepository.findAll().stream()
                .filter(p -> p.getEntitlementId().equals(e.getId()) && p.getPageNumber() == page)
                .findFirst().orElseThrow();
        return storage.get(row.getBucketName(), row.getObjectKey());
    }

    private DocumentVersion processedVersion(int number, int pages) {
        DocumentVersion version = new DocumentVersion();
        version.setProduct(product);
        version.setVersionNumber(number);
        version.setOriginalFilename("book-v" + number + ".pdf");
        version.setFileSizeBytes(2048L);
        version.setRawMinioBucket("secureleaf-raw");
        version.setRawMinioObjectKey("raw/" + product.getId() + "-v" + number + ".pdf");
        version.setPageCount(pages);
        version.setProcessedAt(Instant.now());
        version = documentVersionRepository.save(version);
        for (int page = 1; page <= pages; page++) {
            String key = "products/%d/v%d/page-%d.png".formatted(product.getId(), number, page);
            storage.put("tiles", key, cleanPagePng(), "image/png");
            ContentPage cp = new ContentPage();
            cp.setDocumentVersion(version);
            cp.setPageNumber(page);
            cp.setBucketName("tiles");
            cp.setMinioObjectKey(key);
            contentPageRepository.save(cp);
        }
        return version;
    }

    private Entitlement grantEntitlement(User theBuyer, DocumentVersion version) {
        Order order = new Order();
        order.setBuyer(theBuyer);
        order.setTotalAmountPaise(0L);
        order.transitionTo(OrderStatus.COMPLETED);
        order = orderRepository.save(order);
        Entitlement e = new Entitlement();
        e.setBuyer(theBuyer);
        e.setProduct(product);
        e.setDocumentVersion(version);
        e.setOrder(order);
        e.setStatus(EntitlementStatus.ACTIVE);
        return entitlementRepository.save(e);
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

    private long watermarkRenders() {
        Timer timer = meterRegistry.find(ViewerMetrics.WATERMARK_RENDER).timer();
        return timer == null ? 0 : timer.count();
    }

    private double counter(String name) {
        Counter c = meterRegistry.find(name).counter();
        return c == null ? 0 : c.count();
    }

    private int accessLogCount() {
        Integer n = jdbcTemplate.queryForObject("SELECT count(*) FROM viewer_access_logs", Integer.class);
        return n == null ? 0 : n;
    }

    /** 200x200 (see PreviewControllerIT.fakePng for why small images can silently skip the watermark). */
    private static byte[] cleanPagePng() {
        try {
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                    200, 200, java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }
}
