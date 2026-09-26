package com.secureleaf.notification;

import com.secureleaf.AbstractIntegrationTest;
import com.secureleaf.auth.entity.Role;
import com.secureleaf.auth.entity.User;
import com.secureleaf.auth.entity.UserRole;
import com.secureleaf.auth.repository.UserRepository;
import com.secureleaf.auth.service.JwtService;
import com.secureleaf.content.entity.DocumentVersion;
import com.secureleaf.content.repository.DocumentVersionRepository;
import com.secureleaf.marketplace.entity.Category;
import com.secureleaf.marketplace.entity.Product;
import com.secureleaf.marketplace.entity.ProductStatus;
import com.secureleaf.marketplace.repository.CategoryRepository;
import com.secureleaf.marketplace.repository.ProductRepository;
import com.secureleaf.notification.service.RedisNotificationPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Phase 6, D7/D9 — Server-Sent Events end to end: ticket → connect → a real event, delivered
 * through the REAL Redis Pub/Sub hop (not {@code RecordingNotificationPublisher}), inside 5s.
 *
 * WHY THIS TEST NEEDS ITS OWN SPRING CONTEXT
 * Every other integration test wants {@code RecordingNotificationPublisher} so it never needs a
 * live Pub/Sub round trip. This one is the opposite: it exists specifically to prove the Redis
 * hop works. Activating the {@code sse-real-redis} profile (see {@code RecordingNotificationPublisher}'s
 * {@code @Profile}) drops that bean out of THIS test's context, leaving the real
 * {@link RedisNotificationPublisher} as the sole {@code NotificationPublisher} — Spring Boot Test
 * caches contexts by their exact configuration, so this just means one extra context, not a
 * conflict with the rest of the suite.
 */
@ActiveProfiles({"test", "sse-real-redis"})
class NotificationSseIT extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired private UserRepository userRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private DocumentVersionRepository documentVersionRepository;
    @Autowired private JwtService jwtService;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .executor(Executors.newCachedThreadPool())
            .build();

    private User creator;
    private Product freeProduct;
    private HttpGraphQlTester asCreator;
    private HttpGraphQlTester asBuyer;

    @BeforeEach
    void setUp() {
        creator = makeUser("sse-creator@example.com", "Casey Creator", Role.BUYER, Role.CREATOR);
        User buyer = makeUser("sse-buyer@example.com", "Bala Buyer", Role.BUYER);

        Category category = new Category();
        category.setName("Guides");
        category.setSlug("guides-" + System.nanoTime());
        category = categoryRepository.save(category);

        freeProduct = new Product();
        freeProduct.setCreator(creator);
        freeProduct.setCategory(category);
        freeProduct.setTitle("Free SSE Guide");
        freeProduct.setSlug("free-sse-guide-" + System.nanoTime());
        freeProduct.setDescription("desc");
        freeProduct.setPricePaise(0L);
        freeProduct.setStatus(ProductStatus.LIVE);
        freeProduct = productRepository.save(freeProduct);

        DocumentVersion version = new DocumentVersion();
        version.setProduct(freeProduct);
        version.setOriginalFilename("free.pdf");
        version.setFileSizeBytes(1024L);
        version.setRawMinioBucket("secureleaf-raw");
        version.setRawMinioObjectKey("raw/" + freeProduct.getId() + ".pdf");
        version.setPageCount(3);
        documentVersionRepository.save(version);

        asCreator = tester(creator);
        asBuyer = tester(buyer);
    }

    @Test
    void ticketThenConnect_thenAPurchaseTriggersASaleReceivedEvent_withinFiveSeconds() throws Exception {
        String ticket = asCreator.document("query { notificationStreamTicket }").execute()
                .path("notificationStreamTicket").entity(String.class).get();

        List<String> receivedFrames = new CopyOnWriteArrayList<>();
        HttpResponse<InputStream> connectResponse = openStream(ticket, receivedFrames);
        assertThat(connectResponse.statusCode()).isEqualTo(200);

        // The buyer "buys" the free product — completes synchronously (D10), so
        // FulfillmentService.notifyPurchase runs inside that same mutation call.
        asBuyer.document("""
                mutation($productId: ID!, $key: String!) {
                  initiateOrder(productId: $productId, idempotencyKey: $key) { order { id status } }
                }
                """)
                .variable("productId", freeProduct.getId())
                .variable("key", UUID.randomUUID().toString())
                .execute()
                .path("initiateOrder.order.status").entity(String.class).isEqualTo("COMPLETED");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(receivedFrames).anyMatch(frame -> frame.contains("SALE_RECEIVED")));
    }

    @Test
    void aReusedTicket_getsUnauthorized() throws Exception {
        String ticket = asCreator.document("query { notificationStreamTicket }").execute()
                .path("notificationStreamTicket").entity(String.class).get();

        // First connect consumes the ticket (GETDEL).
        openStream(ticket, new CopyOnWriteArrayList<>());

        // Same ticket again — already gone.
        HttpResponse<String> second = httpClient.send(
                HttpRequest.newBuilder(streamUri(ticket)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(second.statusCode()).isEqualTo(401);
    }

    @Test
    void aMissingTicket_getsUnauthorized() throws Exception {
        HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/notifications/stream")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Opens the SSE connection and starts a background reader appending every line to {@code frames}. */
    private HttpResponse<InputStream> openStream(String ticket, List<String> frames) throws Exception {
        HttpResponse<InputStream> response = httpClient.send(
                HttpRequest.newBuilder(streamUri(ticket)).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());

        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    frames.add(line);
                }
            } catch (IOException ignored) {
                // Stream closed at test teardown — expected.
            }
        }, "sse-test-reader");
        reader.setDaemon(true);
        reader.start();

        return response;
    }

    private URI streamUri(String ticket) {
        return URI.create("http://localhost:" + port + "/api/notifications/stream?ticket=" + ticket);
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

    private HttpGraphQlTester tester(User user) {
        WebTestClient.Builder client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port + "/graphql");
        client.defaultHeader("Authorization", "Bearer " + jwtService.generateAccessToken(user));
        return HttpGraphQlTester.create(client.build());
    }
}
