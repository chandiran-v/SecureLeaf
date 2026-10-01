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
import com.secureleaf.content.watermark.WatermarkRenderer;
import com.secureleaf.marketplace.entity.Category;
import com.secureleaf.marketplace.entity.Product;
import com.secureleaf.marketplace.entity.ProductStatus;
import com.secureleaf.marketplace.repository.CategoryRepository;
import com.secureleaf.marketplace.repository.ProductRepository;
import com.secureleaf.viewer.cache.TileCacheEvictor;
import com.secureleaf.viewer.service.ViewerAccessLogService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Phase 13 acceptance criteria 1-4 (and 5's "cache is on disk" wiring) against the real tile
 * endpoint with {@code tilecache.type=disk}. The renderer is a spy-like stub whose output is its
 * label, so "different bytes for a different session/user" is directly observable.
 */
// The test profile's 2 s lease would lapse while the helper waits out the signature's one-second
// resolution (see Viewer#url), so give this class an ordinary lease.
// Secondary context (own properties => own Spring context, cached and left running): keep its job
// poller off the shared DB, or it steals QUEUED jobs from the main context and fails them because
// its InMemoryStorageService is a different instance (see ProductStatsIT, same rule).
@TestPropertySource(properties = {"drm.session.lease-seconds=60", "processing.worker.enabled=false"})
@Import(TileCacheIT.CountingRendererConfig.class)
class TileCacheIT extends AbstractIntegrationTest {

    @TempDir
    static Path cacheDir;

    @DynamicPropertySource
    static void cacheProperties(DynamicPropertyRegistry registry) {
        registry.add("tilecache.type", () -> "disk");
        registry.add("tilecache.dir", () -> cacheDir.toString());
    }

    static class CountingRenderer implements WatermarkRenderer {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public byte[] applyWatermark(byte[] imageBytes, String label) {
            calls.incrementAndGet();
            return label.getBytes(StandardCharsets.UTF_8);
        }
    }

    @TestConfiguration
    static class CountingRendererConfig {
        @Bean
        @Primary
        CountingRenderer countingRenderer() {
            return new CountingRenderer();
        }
    }

    @LocalServerPort private int port;
    @Autowired private CountingRenderer renderer;
    @Autowired private MockMvc mockMvc;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private DocumentVersionRepository documentVersionRepository;
    @Autowired private ContentPageRepository contentPageRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private EntitlementRepository entitlementRepository;
    @Autowired private InMemoryStorageService storage;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TileCacheEvictor evictor;
    @SpyBean private InMemoryStorageService spiedStorage;
    @SpyBean private ViewerAccessLogService spiedAccessLog;

    private Product product;
    private DocumentVersion version;
    private User buyer;
    private Entitlement buyerEntitlement;

    @BeforeEach
    void fixtures() {
        // TRUNCATE ... RESTART IDENTITY hands every test the same user/session/version ids, hence the
        // same cache keys as the previous test: empty the cache so tests can't hit each other's tiles.
        for (long userId = 1; userId <= 5; userId++) {
            evictor.evictUser(userId);
        }
        renderer.calls.set(0);
        storage.clear();
        User creator = makeUser("creator@cache.test", Role.BUYER, Role.CREATOR);
        buyer = makeUser("buyer@cache.test", Role.BUYER);

        Category category = new Category();
        category.setName("Fiction");
        category.setSlug("fiction-" + System.nanoTime());
        category = categoryRepository.save(category);

        product = new Product();
        product.setCreator(creator);
        product.setCategory(category);
        product.setTitle("Cache Test Book");
        product.setSlug("cache-test-book-" + System.nanoTime());
        product.setDescription("desc");
        product.setPricePaise(0L);
        product.setStatus(ProductStatus.LIVE);
        product.setFreePreviewPages(1);
        product = productRepository.save(product);

        version = new DocumentVersion();
        version.setProduct(product);
        version.setVersionNumber(1);
        version.setOriginalFilename("book.pdf");
        version.setFileSizeBytes(2048L);
        version.setRawMinioBucket("secureleaf-raw");
        version.setRawMinioObjectKey("raw/" + product.getId() + ".pdf");
        version.setPageCount(3);
        version = documentVersionRepository.save(version);

        for (int page = 1; page <= 3; page++) {
            String key = "products/%d/v1/page-%d.png".formatted(product.getId(), page);
            storage.put("tiles", key, new byte[]{1, 2, 3}, "image/png");
            ContentPage cp = new ContentPage();
            cp.setDocumentVersion(version);
            cp.setPageNumber(page);
            cp.setBucketName("tiles");
            cp.setMinioObjectKey(key);
            contentPageRepository.save(cp);
        }
        buyerEntitlement = entitle(buyer);
    }

    // ═══ 1. Second request for the same page in the same session is a hit ═══
    @Test
    void secondRequestSamePageSameSession_isHit_noStorageNoRender_sameBytes() throws Exception {
        Viewer viewer = viewer(buyer);
        double hitsBefore = counter("secureleaf.tilecache.hit");

        byte[] first = viewer.tile(1);
        assertThat(renderer.calls.get()).isEqualTo(1);
        Mockito.clearInvocations(spiedStorage);

        byte[] second = viewer.tile(1);

        assertThat(second).isEqualTo(first);
        assertThat(renderer.calls.get()).as("renderer not called on a hit").isEqualTo(1);
        Mockito.verify(spiedStorage, Mockito.never()).get(Mockito.anyString(), Mockito.anyString());
        assertThat(counter("secureleaf.tilecache.hit")).isEqualTo(hitsBefore + 1);
        assertThat(new String(first, StandardCharsets.UTF_8))
                .as("D1: date + session id, no minute timestamp")
                .matches("buyer@cache\\.test · #\\d+ · \\d{4}-\\d{2}-\\d{2} UTC · s\\d+");
        try (Stream<Path> files = Files.list(cacheDir)) {
            assertThat(files.filter(f -> f.toString().endsWith(".png"))).hasSize(1);
        }
    }

    // ═══ 2. A different session, or a different user, is a miss with different bytes ═══
    @Test
    void differentSession_orDifferentUser_isMissWithDifferentBytes() throws Exception {
        byte[] sessionOne = viewer(buyer).tile(1);
        byte[] sessionTwo = viewer(buyer).tile(1);     // starting again supersedes session one
        assertThat(renderer.calls.get()).isEqualTo(2);
        assertThat(sessionTwo).isNotEqualTo(sessionOne);

        User other = makeUser("other@cache.test", Role.BUYER);
        entitle(other);
        byte[] otherUser = viewer(other).tile(1);
        assertThat(renderer.calls.get()).isEqualTo(3);
        assertThat(otherUser).isNotEqualTo(sessionTwo);
        assertThat(new String(otherUser, StandardCharsets.UTF_8)).startsWith("other@cache.test");
    }

    // ═══ 3. Revocation: 403 even though the entry exists (checks run before the cache) ═══
    @Test
    void afterRevocation_nextRequestIs403_eventhoughEntryIsCached() throws Exception {
        Viewer viewer = viewer(buyer);
        viewer.tile(1);                                 // now cached
        String url = viewer.url(1);                     // minted while still entitled

        buyerEntitlement.setStatus(EntitlementStatus.REVOKED);
        buyerEntitlement.setRevokedAt(Instant.now());
        entitlementRepository.save(buyerEntitlement);   // deliberately NOT evicting the cache

        assertThat(viewer.fetch(url).getResponse().getStatus()).isEqualTo(403);
        assertThat(renderer.calls.get()).isEqualTo(1);
    }

    // ═══ 4. A cache hit still writes an access-log row ═══
    @Test
    void cacheHit_stillWritesAccessLogRow() throws Exception {
        Viewer viewer = viewer(buyer);
        viewer.tile(2);
        viewer.tile(2);
        viewer.tile(2);
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM viewer_access_logs WHERE page_number = 2", Integer.class);
        assertThat(rows).isEqualTo(3);
    }

    // ═══ D5. evictUser removes the entry so the next request renders again ═══
    @Test
    void evictUser_forcesARender() throws Exception {
        Viewer viewer = viewer(buyer);
        viewer.tile(1);
        evictor.evictUser(buyer.getId());
        try (Stream<Path> files = Files.list(cacheDir)) {
            assertThat(files.filter(f -> f.toString().endsWith(".png"))).isEmpty();
        }
        viewer.tile(1);
        assertThat(renderer.calls.get()).isEqualTo(2);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private final class Viewer {
        private final HttpGraphQlTester graphQl;
        private final String auth;
        private final String sessionToken;

        Viewer(User user) {
            auth = "Bearer " + jwtService.generateAccessToken(user);
            WebTestClient.Builder client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port + "/graphql");
            client.defaultHeader("Authorization", auth);
            graphQl = HttpGraphQlTester.create(client.build());
            sessionToken = graphQl.document("""
                    mutation($productId: ID!) {
                      startViewerSession(productId: $productId, deviceFingerprint: "d1") { sessionToken }
                    }
                    """).variable("productId", product.getId())
                    .execute().path("startViewerSession.sessionToken").entity(String.class).get();
        }

        String url(int page) {
            // The signed URL is HMAC(session|page|user|exp) with exp in whole seconds, and each
            // signature is single-use: minting the same page twice inside one second yields the
            // same signature, which the second fetch would be refused as "already used".
            sleepPastTheSecond();
            @SuppressWarnings("unchecked")
            Map<String, Object> pageUrl = graphQl.document("""
                    query($token: String!, $page: Int!) { viewerPageUrl(sessionToken: $token, pageNumber: $page) { url } }
                    """).variable("token", sessionToken).variable("page", page)
                    .execute().path("viewerPageUrl").entity(Map.class).get();
            return (String) pageUrl.get("url");
        }

        MvcResult fetch(String url) throws Exception {
            MvcResult started = mockMvc.perform(get(url).header("Authorization", auth)).andReturn();
            return started.getRequest().isAsyncStarted()
                    ? mockMvc.perform(asyncDispatch(started)).andReturn() : started;
        }

        byte[] tile(int page) throws Exception {
            MvcResult done = fetch(url(page));
            assertThat(done.getResponse().getStatus()).isEqualTo(200);
            assertThat(done.getResponse().getHeader("Cache-Control")).contains("no-store");   // D6
            return done.getResponse().getContentAsByteArray();
        }
    }

    private static void sleepPastTheSecond() {
        try {
            Thread.sleep(1100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Viewer viewer(User user) {
        return new Viewer(user);
    }

    private Entitlement entitle(User user) {
        Order order = new Order();
        order.setBuyer(user);
        order.setTotalAmountPaise(0L);
        order.transitionTo(OrderStatus.COMPLETED);
        order = orderRepository.save(order);
        Entitlement e = new Entitlement();
        e.setBuyer(user);
        e.setProduct(product);
        e.setDocumentVersion(version);
        e.setOrder(order);
        e.setStatus(EntitlementStatus.ACTIVE);
        return entitlementRepository.save(e);
    }

    private double counter(String name) {
        var c = meterRegistry.find(name).counter();
        return c == null ? 0 : c.count();
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
}
