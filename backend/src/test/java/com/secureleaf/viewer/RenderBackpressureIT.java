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
import com.secureleaf.viewer.service.ViewerAccessLogService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 12 acceptance criteria 1-4. The render pool is shrunk to 1 thread + 1 queue slot and the
 * watermark renderer is replaced by a stub that can be made to block, so "saturated" is a state
 * the test controls exactly instead of something to provoke with load.
 */
@TestPropertySource(properties = {
        "render.pool.size=1",
        "render.pool.queue-capacity=1",
        "render.timeout-ms=1500",
        "processing.worker.enabled=false"   // secondary context: its poller would steal jobs from the main one
})
@Import(RenderBackpressureIT.StubRendererConfig.class)
class RenderBackpressureIT extends AbstractIntegrationTest {

    /** Controllable stand-in for the Java2D renderer. */
    static class StubRenderer implements WatermarkRenderer {
        volatile CountDownLatch gate;              // non-null => every render blocks until it opens
        final List<Thread> threads = new CopyOnWriteArrayList<>();
        final AtomicInteger interrupted = new AtomicInteger();
        final AtomicInteger started = new AtomicInteger();

        void reset() {
            gate = null;
            threads.clear();
            interrupted.set(0);
            started.set(0);
        }

        @Override
        public byte[] applyWatermark(byte[] imageBytes, String label) {
            threads.add(Thread.currentThread());
            started.incrementAndGet();
            CountDownLatch g = gate;
            if (g != null) {
                try {
                    g.await();
                } catch (InterruptedException e) {
                    interrupted.incrementAndGet();
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("render interrupted", e);
                }
            }
            return imageBytes;
        }
    }

    @TestConfiguration
    static class StubRendererConfig {
        @Bean
        @Primary
        StubRenderer stubRenderer() {
            return new StubRenderer();
        }
    }

    @LocalServerPort private int port;
    @Autowired private StubRenderer renderer;
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
    @SpyBean private InMemoryStorageService spiedStorage;
    @SpyBean private ViewerAccessLogService spiedAccessLog;

    private User buyer;
    private HttpGraphQlTester asBuyer;
    private String auth;
    private String sessionToken;

    @BeforeEach
    void fixtures() {
        renderer.reset();
        storage.clear();

        User creator = makeUser("creator@render.test", Role.BUYER, Role.CREATOR);
        buyer = makeUser("buyer@render.test", Role.BUYER);

        Category category = new Category();
        category.setName("Fiction");
        category.setSlug("fiction-" + System.nanoTime());
        category = categoryRepository.save(category);

        Product product = new Product();
        product.setCreator(creator);
        product.setCategory(category);
        product.setTitle("Render Test Book");
        product.setSlug("render-test-book-" + System.nanoTime());
        product.setDescription("desc");
        product.setPricePaise(0L);
        product.setStatus(ProductStatus.LIVE);
        product.setFreePreviewPages(1);
        product = productRepository.save(product);

        DocumentVersion version = new DocumentVersion();
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

        auth = "Bearer " + jwtService.generateAccessToken(buyer);
        WebTestClient.Builder client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port + "/graphql");
        client.defaultHeader("Authorization", auth);
        asBuyer = HttpGraphQlTester.create(client.build());

        sessionToken = asBuyer.document("""
                mutation($productId: ID!) {
                  startViewerSession(productId: $productId, deviceFingerprint: "d1") { sessionToken }
                }
                """).variable("productId", product.getId())
                .execute().path("startViewerSession.sessionToken").entity(String.class).get();
    }

    // ═══ 1. The watermark runs on tile-render-*; checks, storage and the log write do not ═══
    @Test
    void watermarkRunsOnRenderThread_storageAndAccessLogDoNot() throws Exception {
        List<Thread> storageThreads = new CopyOnWriteArrayList<>();
        List<Thread> logThreads = new CopyOnWriteArrayList<>();
        Mockito.doAnswer(inv -> {
            storageThreads.add(Thread.currentThread());
            return inv.callRealMethod();
        }).when(spiedStorage).get(Mockito.anyString(), Mockito.anyString());
        Mockito.doAnswer(inv -> {
            logThreads.add(Thread.currentThread());
            return inv.callRealMethod();
        }).when(spiedAccessLog).record(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyInt(),
                Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());

        tileSettled(1).andExpectStatus200();

        assertThat(renderer.threads).hasSize(1);
        assertThat(renderer.threads.get(0).getName()).startsWith("tile-render-");
        assertThat(renderer.threads.get(0).isVirtual()).isFalse();
        assertThat(storageThreads).isNotEmpty()
                .noneMatch(t -> t.getName().startsWith("tile-render-"));
        assertThat(logThreads).hasSize(1);
        assertThat(logThreads.get(0).getName()).doesNotStartWith("tile-render-");
        assertThat(logThreads.get(0).isVirtual()).isTrue();
    }

    // ═══ 2 + 4. Saturated: next request is 503 + Retry-After at once; heartbeat stays fast ═══
    @Test
    void saturatedPool_rejectsImmediatelyWith503_andHeartbeatStaysFast() throws Exception {
        renderer.gate = new CountDownLatch(1);
        double rejectedBefore = counter("secureleaf.render.rejected");

        MvcResult running = start(1);    // takes the only render thread and blocks in it
        await().atMost(Duration.ofSeconds(2)).until(() -> renderer.started.get() == 1);
        MvcResult queued = start(2);     // fills the single queue slot
        assertThat(running.getRequest().isAsyncStarted()).isTrue();
        assertThat(queued.getRequest().isAsyncStarted()).isTrue();

        long began = System.nanoTime();
        MvcResult rejected = mockMvc.perform(get(tileUrl(3)).header("Authorization", auth)).andReturn();
        long rejectedMillis = Duration.ofNanos(System.nanoTime() - began).toMillis();

        assertThat(rejected.getResponse().getStatus()).isEqualTo(503);
        assertThat(rejected.getResponse().getHeader("Retry-After")).isEqualTo("1");
        assertThat(rejectedMillis).as("rejection must not wait for the 1500 ms timeout").isLessThan(500);
        assertThat(counter("secureleaf.render.rejected")).isEqualTo(rejectedBefore + 1);

        // Bulkhead proof (criterion 4): a heartbeat is served by other threads, so it isn't queued.
        heartbeat();   // warm-up: first GraphQL call pays class-loading
        long hbBegan = System.nanoTime();
        heartbeat();
        long heartbeatMillis = Duration.ofNanos(System.nanoTime() - hbBegan).toMillis();
        assertThat(heartbeatMillis).as("heartbeat while the render pool is saturated").isLessThan(200);

        renderer.gate.countDown();       // let the two queued tiles finish normally
        mockMvc.perform(asyncDispatch(running)).andExpect(status().isOk());
        mockMvc.perform(asyncDispatch(queued)).andExpect(status().isOk());
    }

    // ═══ 3. A render over the timeout is 503, and its pool thread is freed (not leaked) ═══
    @Test
    void renderOverTimeout_returns503_andReleasesThePoolThread() throws Exception {
        renderer.gate = new CountDownLatch(1);   // never opened: the render only ends by interrupt
        double timeoutsBefore = counter("secureleaf.render.timeout");

        MvcResult stuck = start(1);
        await().atMost(Duration.ofSeconds(2)).until(() -> renderer.started.get() == 1);
        assertThat(gauge("secureleaf.render.active")).isEqualTo(1.0);

        mockMvc.perform(asyncDispatch(stuck))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"));

        await().atMost(Duration.ofSeconds(2)).untilAsserted(
                () -> assertThat(gauge("secureleaf.render.active")).isEqualTo(0.0));
        assertThat(renderer.interrupted.get()).isEqualTo(1);
        assertThat(counter("secureleaf.render.timeout")).isEqualTo(timeoutsBefore + 1);

        // and the pool works again
        renderer.gate = null;
        tileSettled(2).andExpectStatus200();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String tileUrl(int page) {
        @SuppressWarnings("unchecked")
        Map<String, Object> pageUrl = asBuyer.document("""
                query($token: String!, $page: Int!) { viewerPageUrl(sessionToken: $token, pageNumber: $page) { url } }
                """).variable("token", sessionToken).variable("page", page)
                .execute().path("viewerPageUrl").entity(Map.class).get();
        return (String) pageUrl.get("url");
    }

    /** Sends the tile request and returns once the server has gone async (or answered). */
    private MvcResult start(int page) throws Exception {
        return mockMvc.perform(get(tileUrl(page)).header("Authorization", auth)).andReturn();
    }

    private Settled tileSettled(int page) throws Exception {
        MvcResult started = start(page);
        MvcResult done = mockMvc.perform(asyncDispatch(started)).andReturn();
        return new Settled(done.getResponse().getStatus());
    }

    private record Settled(int status) {
        void andExpectStatus200() {
            assertThat(status).isEqualTo(200);
        }
    }

    private void heartbeat() {
        asBuyer.document("mutation($t: String!) { viewerHeartbeat(sessionToken: $t) { status } }")
                .variable("t", sessionToken).execute().path("viewerHeartbeat.status").entity(String.class).get();
    }

    private double counter(String name) {
        var c = meterRegistry.find(name).counter();
        return c == null ? 0 : c.count();
    }

    private double gauge(String name) {
        return meterRegistry.get(name).gauge().value();
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
