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
import com.secureleaf.viewer.entity.ViewerSessionEndReason;
import com.secureleaf.viewer.service.ViewerSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 05A acceptance tests — numbered 1-13 below match the spec's acceptance-criteria list
 * exactly (phase-05a-secure-viewer-backend.md), so a failing test names the broken promise.
 */
class ViewerIT extends AbstractIntegrationTest {

    private static final String CLEAN_PAGE_PNG_MARKER = "clean-page-bytes";

    @LocalServerPort
    private int port;

    @Autowired private UserRepository userRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private DocumentVersionRepository documentVersionRepository;
    @Autowired private ContentPageRepository contentPageRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private EntitlementRepository entitlementRepository;
    @Autowired private InMemoryStorageService storage;
    @Autowired private JwtService jwtService;
    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ViewerSessionService viewerSessionService;

    private User creator;
    private User buyer;
    private User otherBuyer;
    private Product product;
    private DocumentVersion documentVersion;
    private Entitlement entitlement;

    private HttpGraphQlTester asBuyer;
    private HttpGraphQlTester asOtherBuyer;

    @BeforeEach
    void setUpViewerFixtures() {
        storage.clear();

        creator = makeUser("creator@viewer.test", "Vera Creator", Role.BUYER, Role.CREATOR);
        buyer = makeUser("buyer@viewer.test", "Bo Buyer", Role.BUYER);
        otherBuyer = makeUser("other@viewer.test", "Oscar Other", Role.BUYER);

        Category category = new Category();
        category.setName("Fiction");
        category.setSlug("fiction-" + System.nanoTime());
        category = categoryRepository.save(category);

        product = new Product();
        product.setCreator(creator);
        product.setCategory(category);
        product.setTitle("Viewer Test Book");
        product.setSlug("viewer-test-book-" + System.nanoTime());
        product.setDescription("desc");
        product.setPricePaise(0L);
        product.setStatus(ProductStatus.LIVE);
        product.setFreePreviewPages(1);
        product = productRepository.save(product);

        documentVersion = new DocumentVersion();
        documentVersion.setProduct(product);
        documentVersion.setVersionNumber(1);
        documentVersion.setOriginalFilename("book.pdf");
        documentVersion.setFileSizeBytes(2048L);
        documentVersion.setRawMinioBucket("secureleaf-raw");
        documentVersion.setRawMinioObjectKey("raw/" + product.getId() + ".pdf");
        documentVersion.setPageCount(5);
        documentVersion = documentVersionRepository.save(documentVersion);

        for (int page = 1; page <= 5; page++) {
            String key = "products/%d/v1/page-%d.png".formatted(product.getId(), page);
            storage.put("tiles", key, cleanPagePng(), "image/png");

            ContentPage cp = new ContentPage();
            cp.setDocumentVersion(documentVersion);
            cp.setPageNumber(page);
            cp.setBucketName("tiles");
            cp.setMinioObjectKey(key);
            contentPageRepository.save(cp);
        }

        entitlement = grantEntitlement(buyer, product, documentVersion);

        asBuyer = tester(buyer);
        asOtherBuyer = tester(otherBuyer);
    }

    // ═══ 1. No entitlement → NOT_ENTITLED ══════════════════════════════════
    @Test
    void startViewerSession_withoutEntitlement_returnsNotEntitled() {
        asOtherBuyer.document(START)
                .variable("productId", product.getId()).variable("fp", "device-1")
                .execute().errors().expect(e -> "NOT_ENTITLED".equals(e.getExtensions().get("code"))).verify();
    }

    // ═══ 2. Happy path: start → viewerPageUrl → GET tile → 200, bytes differ, no-store ═══
    @Test
    void happyPath_returnsWatermarkedTileAndNoStoreHeaders() throws Exception {
        Session session = startSession(asBuyer, "device-1");

        Map<String, Object> pageUrl = pageUrl(asBuyer, session.token(), 1);
        String url = (String) pageUrl.get("url");

        byte[] bytes = mockMvc.perform(get(url).header("Authorization", "Bearer " + jwtService.generateAccessToken(buyer)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store, private"))
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(bytes).isNotEqualTo(cleanPagePng());
        assertThat(accessLogCount()).isEqualTo(1);
    }

    // ═══ 3. Same signed URL used twice → second use is 403 ═════════════════
    @Test
    void reusedSignedUrl_returns403() throws Exception {
        Session session = startSession(asBuyer, "device-1");
        String url = (String) pageUrl(asBuyer, session.token(), 1).get("url");
        String auth = "Bearer " + jwtService.generateAccessToken(buyer);

        mockMvc.perform(get(url).header("Authorization", auth)).andExpect(status().isOk());
        mockMvc.perform(get(url).header("Authorization", auth)).andExpect(status().isForbidden());
        assertThat(accessLogCount()).isEqualTo(1);
    }

    // ═══ 4. Expired / tampered URL → 403 ════════════════════════════════════
    @Test
    void expiredSignature_returns403() throws Exception {
        Session session = startSession(asBuyer, "device-1");
        long pastExp = Instant.now().minusSeconds(5).getEpochSecond();
        String auth = "Bearer " + jwtService.generateAccessToken(buyer);

        mockMvc.perform(get("/api/viewer/tiles/{id}/1?exp={exp}&sig=anything", session.sessionId(), pastExp)
                        .header("Authorization", auth))
                .andExpect(status().isForbidden());
    }

    @Test
    void tamperedPageNumber_returns403() throws Exception {
        Session session = startSession(asBuyer, "device-1");
        Map<String, Object> pageUrl = pageUrl(asBuyer, session.token(), 1);
        String url = ((String) pageUrl.get("url")).replaceFirst("/1\\?", "/2?");
        String auth = "Bearer " + jwtService.generateAccessToken(buyer);

        mockMvc.perform(get(url).header("Authorization", auth)).andExpect(status().isForbidden());
    }

    @Test
    void tamperedSessionIdInUrl_returns403() throws Exception {
        Session session = startSession(asBuyer, "device-1");
        Map<String, Object> pageUrl = pageUrl(asBuyer, session.token(), 1);
        String url = ((String) pageUrl.get("url")).replaceFirst("/tiles/" + session.sessionId() + "/", "/tiles/999999/");
        String auth = "Bearer " + jwtService.generateAccessToken(buyer);

        mockMvc.perform(get(url).header("Authorization", auth)).andExpect(status().isForbidden());
    }

    // ═══ 5. Valid URL fetched with another user's JWT → 403 ═════════════════
    @Test
    void validUrlWithAnotherUsersJwt_returns403() throws Exception {
        Session session = startSession(asBuyer, "device-1");
        String url = (String) pageUrl(asBuyer, session.token(), 1).get("url");
        String otherAuth = "Bearer " + jwtService.generateAccessToken(otherBuyer);

        mockMvc.perform(get(url).header("Authorization", otherAuth)).andExpect(status().isForbidden());
    }

    // ═══ 6. Second startViewerSession supersedes the first ═════════════════
    @Test
    void secondSession_supersedesFirst() throws Exception {
        Session first = startSession(asBuyer, "device-1");
        String firstUrl = (String) pageUrl(asBuyer, first.token(), 1).get("url");

        Session second = startSession(asBuyer, "device-2");
        assertThat(second.sessionId()).isNotEqualTo(first.sessionId());

        asBuyer.document(HEARTBEAT).variable("token", first.token())
                .execute().path("viewerHeartbeat.status").entity(String.class).isEqualTo("SUPERSEDED");

        String auth = "Bearer " + jwtService.generateAccessToken(buyer);
        mockMvc.perform(get(firstUrl).header("Authorization", auth)).andExpect(status().isConflict());

        assertThat(endReasonOf(first.sessionId())).isEqualTo("SUPERSEDED");
    }

    // ═══ 7. Lapsed lease → heartbeat EXPIRED, sweeper ends the row ═════════
    @Test
    void lapsedLease_heartbeatReportsExpired_andSweeperEndsRow() throws Exception {
        Session session = startSession(asBuyer, "device-1");

        // drm.session.lease-seconds is 2 in the test profile — sleep past it so the Redis
        // active-session key naturally expires, without a 45s+ wait for the real lease.
        Thread.sleep(2_500);

        asBuyer.document(HEARTBEAT).variable("token", session.token())
                .execute().path("viewerHeartbeat.status").entity(String.class).isEqualTo("EXPIRED");

        assertThat(endReasonOf(session.sessionId())).isNull(); // sweeper hasn't run yet

        viewerSessionService.sweepExpiredLeases();

        assertThat(endReasonOf(session.sessionId())).isEqualTo("EXPIRED");
    }

    // ═══ 8. Revoking the entitlement mid-session → next tile fetch is 403 ══
    @Test
    void revokedEntitlement_midSession_returns403OnNextFetch() throws Exception {
        Session session = startSession(asBuyer, "device-1");
        String url = (String) pageUrl(asBuyer, session.token(), 1).get("url");

        entitlement.setStatus(EntitlementStatus.REVOKED);
        entitlement.setRevokedAt(Instant.now());
        entitlementRepository.save(entitlement);

        String auth = "Bearer " + jwtService.generateAccessToken(buyer);
        mockMvc.perform(get(url).header("Authorization", auth)).andExpect(status().isForbidden());
    }

    // ═══ 9. Page number outside 1..pageCount → 404 ══════════════════════════
    @Test
    void pageNumberOutOfRange_returns404() throws Exception {
        Session session = startSession(asBuyer, "device-1");
        Map<String, Object> pageUrl = pageUrl(asBuyer, session.token(), 999);
        String url = (String) pageUrl.get("url");
        String auth = "Bearer " + jwtService.generateAccessToken(buyer);

        mockMvc.perform(get(url).header("Authorization", auth)).andExpect(status().isNotFound());
    }

    // ═══ 10. UNPUBLISHED product is still viewable by an entitled buyer (D8) ═
    @Test
    void unpublishedProduct_stillViewableByEntitledBuyer() throws Exception {
        product.setStatus(ProductStatus.UNPUBLISHED);
        productRepository.save(product);

        Session session = startSession(asBuyer, "device-1");
        String url = (String) pageUrl(asBuyer, session.token(), 1).get("url");
        String auth = "Bearer " + jwtService.generateAccessToken(buyer);

        mockMvc.perform(get(url).header("Authorization", auth)).andExpect(status().isOk());
    }

    // ═══ 11. Exactly one access-log row per successful fetch; none on failure ═
    @Test
    void accessLog_onlyWrittenOnSuccess_withCorrectPageAndCorrelationId() throws Exception {
        Session session = startSession(asBuyer, "device-1");
        String url = (String) pageUrl(asBuyer, session.token(), 3).get("url");
        String auth = "Bearer " + jwtService.generateAccessToken(buyer);

        mockMvc.perform(get(url).header("Authorization", auth).header("X-Correlation-Id", "corr-123"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-Id", "corr-123"));

        assertThat(accessLogCount()).isEqualTo(1);
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT page_number, correlation_id FROM viewer_access_logs LIMIT 1");
        assertThat(row.get("page_number")).isEqualTo(3);
        assertThat(row.get("correlation_id")).isEqualTo("corr-123");

        // A failed fetch (reused URL) must create no new row.
        mockMvc.perform(get(url).header("Authorization", auth)).andExpect(status().isForbidden());
        assertThat(accessLogCount()).isEqualTo(1);
    }

    // ═══ 12. Unauthenticated tile request → 401 ═════════════════════════════
    @Test
    void unauthenticatedTileRequest_returns401() throws Exception {
        Session session = startSession(asBuyer, "device-1");
        String url = (String) pageUrl(asBuyer, session.token(), 1).get("url");

        mockMvc.perform(get(url)).andExpect(status().isUnauthorized());
    }

    // ═══ Helpers ═════════════════════════════════════════════════════════════

    private static final String START = """
            mutation($productId: ID!, $fp: String!) {
              startViewerSession(productId: $productId, deviceFingerprint: $fp) {
                sessionId sessionToken productId pageCount heartbeatIntervalSeconds expiresAt
              }
            }
            """;

    private static final String HEARTBEAT = """
            mutation($token: String!) {
              viewerHeartbeat(sessionToken: $token) { status expiresAt }
            }
            """;

    private static final String PAGE_URL = """
            query($token: String!, $page: Int!) {
              viewerPageUrl(sessionToken: $token, pageNumber: $page) { url expiresAt }
            }
            """;

    private record Session(String sessionId, String token) {
    }

    private Session startSession(HttpGraphQlTester as, String deviceFingerprint) {
        Map<String, Object> result = as.document(START)
                .variable("productId", product.getId()).variable("fp", deviceFingerprint)
                .execute().path("startViewerSession").entity(Map.class).get();
        return new Session(String.valueOf(result.get("sessionId")), (String) result.get("sessionToken"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> pageUrl(HttpGraphQlTester as, String sessionToken, int pageNumber) {
        return as.document(PAGE_URL)
                .variable("token", sessionToken).variable("page", pageNumber)
                .execute().path("viewerPageUrl").entity(Map.class).get();
    }

    private User makeUser(String email, String name, Role... roles) {
        User user = new User();
        user.setEmail(email);
        user.setDisplayName(name);
        user.setPasswordHash("hash");
        for (Role role : roles) {
            UserRole userRole = new UserRole();
            userRole.setUser(user);
            userRole.setRole(role);
            user.getRoles().add(userRole);
        }
        return userRepository.save(user);
    }

    private Entitlement grantEntitlement(User theBuyer, Product theProduct, DocumentVersion version) {
        Order order = new Order();
        order.setBuyer(theBuyer);
        order.setTotalAmountPaise(0L);
        order.transitionTo(OrderStatus.COMPLETED);
        order = orderRepository.save(order);

        Entitlement e = new Entitlement();
        e.setBuyer(theBuyer);
        e.setProduct(theProduct);
        e.setDocumentVersion(version);
        e.setOrder(order);
        e.setStatus(EntitlementStatus.ACTIVE);
        return entitlementRepository.save(e);
    }

    private HttpGraphQlTester tester(User user) {
        WebTestClient.Builder client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port + "/graphql");
        client.defaultHeader("Authorization", "Bearer " + jwtService.generateAccessToken(user));
        return HttpGraphQlTester.create(client.build());
    }

    private int accessLogCount() {
        Integer n = jdbcTemplate.queryForObject("SELECT count(*) FROM viewer_access_logs", Integer.class);
        return n == null ? 0 : n;
    }

    private String endReasonOf(String sessionId) {
        return jdbcTemplate.queryForObject(
                "SELECT end_reason FROM viewer_sessions WHERE id = ?", String.class, Long.valueOf(sessionId));
    }

    /** 200x200, not 10x10 — see PreviewControllerIT.fakePng()'s comment for why size matters here. */
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
