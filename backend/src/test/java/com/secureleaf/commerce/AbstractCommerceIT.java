package com.secureleaf.commerce;

import com.secureleaf.AbstractIntegrationTest;
import com.secureleaf.auth.entity.Role;
import com.secureleaf.auth.entity.User;
import com.secureleaf.auth.entity.UserRole;
import com.secureleaf.auth.repository.UserRepository;
import com.secureleaf.auth.service.JwtService;
import com.secureleaf.commerce.gateway.MockWebhookSender;
import com.secureleaf.commerce.gateway.PaymentGatewayProperties;
import com.secureleaf.commerce.gateway.RazorpaySignatures;
import com.secureleaf.content.entity.DocumentVersion;
import com.secureleaf.content.repository.DocumentVersionRepository;
import com.secureleaf.marketplace.entity.Category;
import com.secureleaf.marketplace.entity.Product;
import com.secureleaf.marketplace.entity.ProductStatus;
import com.secureleaf.marketplace.repository.CategoryRepository;
import com.secureleaf.marketplace.repository.ProductRepository;
import com.secureleaf.notification.RecordingNotificationPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Shared fixtures for the Phase 4 integration tests: a creator with one LIVE paid product
 * and one LIVE free product (both with a document version, which fulfilment requires), two
 * buyers, JWT-authenticated GraphQL testers, and helpers that play the gateway's part —
 * paying through the mock checkout and posting correctly signed webhooks.
 */
abstract class AbstractCommerceIT extends AbstractIntegrationTest {

    protected static final long PRICE_PAISE = 49_900;   // ₹499

    @LocalServerPort
    private int port;

    @Autowired protected UserRepository userRepository;
    @Autowired protected CategoryRepository categoryRepository;
    @Autowired protected ProductRepository productRepository;
    @Autowired protected DocumentVersionRepository documentVersionRepository;
    @Autowired protected JdbcTemplate jdbcTemplate;
    @Autowired protected JwtService jwtService;
    @Autowired protected MockMvc mockMvc;
    @Autowired protected MockWebhookSender mockWebhookSender;
    @Autowired protected PaymentGatewayProperties gatewayProperties;
    @Autowired protected RecordingNotificationPublisher notificationPublisher;

    protected User creator;
    protected User buyer;
    protected User otherBuyer;
    protected Product paidProduct;
    protected Product freeProduct;

    protected HttpGraphQlTester anonymous;
    protected HttpGraphQlTester asCreator;
    protected HttpGraphQlTester asBuyer;
    protected HttpGraphQlTester asOtherBuyer;

    @BeforeEach
    void setUpCommerceFixtures() {
        // Tables are already truncated by AbstractIntegrationTest.truncateAllTables().
        notificationPublisher.clear();

        creator = makeUser("creator@example.com", "Casey Creator", Role.BUYER, Role.CREATOR);
        buyer = makeUser("buyer@example.com", "Bala Buyer", Role.BUYER);
        otherBuyer = makeUser("other@example.com", "Olive Other", Role.BUYER);

        Category category = new Category();
        category.setName("Guides");
        category.setSlug("guides");
        category = categoryRepository.save(category);

        paidProduct = makeLiveProduct(category, "Paid Guide", PRICE_PAISE);
        freeProduct = makeLiveProduct(category, "Free Guide", 0);

        anonymous = tester(null);
        asCreator = tester(creator);
        asBuyer = tester(buyer);
        asOtherBuyer = tester(otherBuyer);
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    protected User makeUser(String email, String name, Role... roles) {
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

    protected Product makeLiveProduct(Category category, String title, long pricePaise) {
        Product p = new Product();
        p.setCreator(creator);
        p.setCategory(category);
        p.setTitle(title);
        p.setDescription("A description of " + title);
        p.setSlug(title.toLowerCase().replace(' ', '-') + "-" + System.nanoTime());
        p.setPricePaise(pricePaise);
        p.setStatus(ProductStatus.LIVE);
        p = productRepository.save(p);

        DocumentVersion v = new DocumentVersion();
        v.setProduct(p);
        v.setOriginalFilename(title + ".pdf");
        v.setFileSizeBytes(1024L);
        v.setRawMinioBucket("secureleaf-raw");
        v.setRawMinioObjectKey("raw/" + p.getId() + ".pdf");
        v.setPageCount(10);
        documentVersionRepository.save(v);
        return p;
    }

    protected HttpGraphQlTester tester(User user) {
        WebTestClient.Builder client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port + "/graphql");
        if (user != null) {
            client.defaultHeader("Authorization", "Bearer " + jwtService.generateAccessToken(user));
        }
        return HttpGraphQlTester.create(client.build());
    }

    // ── Buyer actions ───────────────────────────────────────────────────────

    protected static final String INITIATE = """
            mutation($productId: ID!, $key: String!) {
              initiateOrder(productId: $productId, idempotencyKey: $key) {
                order { id status totalAmountPaise gatewayOrderId product { id title } }
                gatewayOrderId gatewayKeyId currency
              }
            }
            """;

    protected static final String VERIFY = """
            mutation($input: VerifyPaymentInput!) {
              verifyPayment(input: $input) { id status failureReason }
            }
            """;

    /** Initiates an order and returns {orderId, gatewayOrderId}. */
    protected Map<String, String> initiate(HttpGraphQlTester as, Product product, String key) {
        String orderId = as.document(INITIATE)
                .variable("productId", product.getId()).variable("key", key)
                .execute().path("initiateOrder.order.id").entity(String.class).get();
        String gatewayOrderId = as.document(INITIATE)   // replay with the same key — also proves layer 1
                .variable("productId", product.getId()).variable("key", key)
                .execute().path("initiateOrder.gatewayOrderId").entity(String.class).get();
        return Map.of("orderId", orderId, "gatewayOrderId", gatewayOrderId == null ? "" : gatewayOrderId);
    }

    protected static String newKey() {
        return UUID.randomUUID().toString();
    }

    // ── Gateway actions (what Razorpay would do) ────────────────────────────

    /** Pays through the mock checkout; returns the raw result of the REST call. */
    protected ResultActions payAtGateway(String gatewayOrderId, String outcome) throws Exception {
        return mockMvc.perform(post("/api/mock-gateway/orders/{id}/pay", gatewayOrderId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"outcome\":\"" + outcome + "\"}"));
    }

    protected String checkoutSignature(String gatewayOrderId, String paymentId) {
        return RazorpaySignatures.checkoutSignature(gatewayOrderId, paymentId, gatewayProperties.keySecret());
    }

    /** Posts a Razorpay-format webhook, correctly signed unless a signature is given. */
    protected ResultActions postWebhook(String event, String gatewayOrderId, String paymentId, long amountPaise,
                                        String eventId, String signatureOverride) throws Exception {
        String body = mockWebhookSender.buildEventBody(event, gatewayOrderId, paymentId, amountPaise, "INR",
                MockWebhookSender.EVENT_FAILED.equals(event) ? "Card declined" : null);
        String signature = signatureOverride != null
                ? signatureOverride
                : RazorpaySignatures.webhookSignature(body, gatewayProperties.webhookSecret());
        return mockMvc.perform(post("/api/webhooks/razorpay")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Razorpay-Signature", signature)
                .header("X-Razorpay-Event-Id", eventId)
                .content(body));
    }

    protected ResultActions postWebhook(String event, String gatewayOrderId, String paymentId, long amountPaise,
                                        String eventId) throws Exception {
        return postWebhook(event, gatewayOrderId, paymentId, amountPaise, eventId, null);
    }

    // ── DB assertions ───────────────────────────────────────────────────────

    protected int count(String sql, Object... args) {
        Integer n = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    protected int activeEntitlements(User who, Product product) {
        return count("SELECT count(*) FROM entitlements WHERE buyer_id = ? AND product_id = ? AND status = 'ACTIVE'",
                who.getId(), product.getId());
    }

    protected int totalSales(Product product) {
        return count("SELECT total_sales FROM products WHERE id = ?", product.getId());
    }

    protected String orderStatus(String orderId) {
        return jdbcTemplate.queryForObject("SELECT status::text FROM orders WHERE id = ?", String.class, Long.valueOf(orderId));
    }
}
