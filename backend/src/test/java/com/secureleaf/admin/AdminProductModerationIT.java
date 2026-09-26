package com.secureleaf.admin;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 8 acceptance criterion 6 (phase-08-admin-panel.md). */
class AdminProductModerationIT extends AbstractIntegrationTest {

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

    private User admin;
    private User creator;
    private User buyer;
    private Product product;
    private HttpGraphQlTester asAdmin;
    private HttpGraphQlTester asCreator;
    private HttpGraphQlTester asBuyer;

    private static final String TAKE_DOWN = """
            mutation($id: ID!, $reason: String!) {
              takeDownProduct(productId: $id, reason: $reason) { id status takedownReason }
            }
            """;
    private static final String RESTORE = """
            mutation($id: ID!) { restoreProduct(productId: $id) { id status takedownReason } }
            """;
    private static final String REPUBLISH = """
            mutation($id: ID!) { republishProduct(productId: $id) { id status } }
            """;
    private static final String MARKETPLACE = """
            query { products(page: 0, size: 20) { content { id } } }
            """;
    private static final String MY_LIBRARY = """
            query { myLibrary { product { id takedownReason } } }
            """;
    private static final String MY_PRODUCTS = """
            query { myProducts { id status takedownReason } }
            """;
    private static final String START_SESSION = """
            mutation($productId: ID!, $fp: String!) {
              startViewerSession(productId: $productId, deviceFingerprint: $fp) { sessionId }
            }
            """;

    @BeforeEach
    void setUp() {
        storage.clear();
        admin = makeUser("admin@secureleaf.test", "Ada Admin", Role.BUYER, Role.ADMIN);
        creator = makeUser("creator@secureleaf.test", "Casey Creator", Role.BUYER, Role.CREATOR);
        buyer = makeUser("buyer@secureleaf.test", "Bo Buyer", Role.BUYER);

        Category category = new Category();
        category.setName("Guides");
        category.setSlug("guides-" + System.nanoTime());
        category = categoryRepository.save(category);

        product = new Product();
        product.setCreator(creator);
        product.setCategory(category);
        product.setTitle("Moderated Book");
        product.setSlug("moderated-book-" + System.nanoTime());
        product.setDescription("a book that will be moderated");
        product.setPricePaise(0L);
        product.setStatus(ProductStatus.LIVE);
        product = productRepository.save(product);

        DocumentVersion documentVersion = new DocumentVersion();
        documentVersion.setProduct(product);
        documentVersion.setVersionNumber(1);
        documentVersion.setOriginalFilename("book.pdf");
        documentVersion.setFileSizeBytes(2048L);
        documentVersion.setRawMinioBucket("secureleaf-raw");
        documentVersion.setRawMinioObjectKey("raw/" + product.getId() + ".pdf");
        documentVersion.setPageCount(1);
        documentVersion = documentVersionRepository.save(documentVersion);

        String key = "products/%d/v1/page-1.png".formatted(product.getId());
        storage.put("tiles", key, new byte[]{1, 2, 3}, "image/png");
        ContentPage cp = new ContentPage();
        cp.setDocumentVersion(documentVersion);
        cp.setPageNumber(1);
        cp.setBucketName("tiles");
        cp.setMinioObjectKey(key);
        contentPageRepository.save(cp);

        Order order = new Order();
        order.setBuyer(buyer);
        order.setTotalAmountPaise(0L);
        order.transitionTo(OrderStatus.COMPLETED);
        order = orderRepository.save(order);

        Entitlement e = new Entitlement();
        e.setBuyer(buyer);
        e.setProduct(product);
        e.setDocumentVersion(documentVersion);
        e.setOrder(order);
        e.setStatus(EntitlementStatus.ACTIVE);
        entitlementRepository.save(e);

        asAdmin = tester(admin);
        asCreator = tester(creator);
        asBuyer = tester(buyer);
    }

    @Test
    void takeDown_hidesFromMarketplace_keepsLibraryAndViewerAccess_blocksRepublish_untilRestored() {
        // Visible before takedown.
        List<String> beforeIds = asBuyer.document(MARKETPLACE).execute()
                .path("products.content[*].id").entityList(String.class).get();
        assertThat(beforeIds).contains(String.valueOf(product.getId()));

        // Admin takes it down.
        asAdmin.document(TAKE_DOWN).variable("id", product.getId()).variable("reason", "copyright complaint")
                .execute()
                .path("takeDownProduct.status").entity(String.class).isEqualTo("UNPUBLISHED")
                .path("takeDownProduct.takedownReason").entity(String.class).isEqualTo("copyright complaint");

        // No longer in the public marketplace listing.
        List<String> afterIds = asBuyer.document(MARKETPLACE).execute()
                .path("products.content[*].id").entityList(String.class).get();
        assertThat(afterIds).doesNotContain(String.valueOf(product.getId()));

        // The buyer's library still shows it, with the reason surfaced.
        var library = asBuyer.document(MY_LIBRARY).execute();
        assertThat(library.path("myLibrary[*].product.id").entityList(String.class).get())
                .contains(String.valueOf(product.getId()));
        assertThat(library.path("myLibrary[0].product.takedownReason").entity(String.class).get())
                .isEqualTo("copyright complaint");

        // The viewer still works for the entitled buyer.
        asBuyer.document(START_SESSION).variable("productId", product.getId()).variable("fp", "device-1")
                .execute().path("startViewerSession.sessionId").hasValue();

        // The creator sees the reason and cannot republish.
        var myProducts = asCreator.document(MY_PRODUCTS).execute();
        assertThat(myProducts.path("myProducts[0].takedownReason").entity(String.class).get())
                .isEqualTo("copyright complaint");
        asCreator.document(REPUBLISH).variable("id", product.getId()).execute()
                .errors().expect(err -> "INVALID_STATE_TRANSITION".equals(err.getExtensions().get("code"))).verify();

        // Admin restores it.
        asAdmin.document(RESTORE).variable("id", product.getId()).execute()
                .path("restoreProduct.status").entity(String.class).isEqualTo("LIVE")
                .path("restoreProduct.takedownReason").valueIsNull();

        // Back in the marketplace.
        List<String> restoredIds = asBuyer.document(MARKETPLACE).execute()
                .path("products.content[*].id").entityList(String.class).get();
        assertThat(restoredIds).contains(String.valueOf(product.getId()));
    }

    @Test
    void takeDown_onNonLiveProduct_isRejected() {
        product.setStatus(ProductStatus.DRAFT);
        productRepository.save(product);

        asAdmin.document(TAKE_DOWN).variable("id", product.getId()).variable("reason", "x")
                .execute().errors().expect(e -> "INVALID_STATE_TRANSITION".equals(e.getExtensions().get("code"))).verify();
    }

    @Test
    void restore_onProductNeverTakenDown_isRejected() {
        asAdmin.document(RESTORE).variable("id", product.getId())
                .execute().errors().expect(e -> "INVALID_STATE_TRANSITION".equals(e.getExtensions().get("code"))).verify();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

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
