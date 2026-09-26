package com.secureleaf.admin;

import com.secureleaf.AbstractIntegrationTest;
import com.secureleaf.auth.dto.AuthPayload;
import com.secureleaf.auth.entity.AccountStatus;
import com.secureleaf.auth.entity.Role;
import com.secureleaf.auth.entity.User;
import com.secureleaf.auth.entity.UserRole;
import com.secureleaf.auth.repository.UserRepository;
import com.secureleaf.auth.service.AuthService;
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
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 8 acceptance criteria 3 and 4 (phase-08-admin-panel.md).
 */
class AdminUserManagementIT extends AbstractIntegrationTest {

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
    @Autowired private AuthService authService;
    @Autowired private MockMvc mockMvc;
    @PersistenceContext private EntityManager entityManager;

    private User admin;
    private HttpGraphQlTester asAdmin;
    private Category category;

    @BeforeEach
    void setUp() {
        storage.clear();
        admin = makeUser("admin@secureleaf.test", "Ada Admin", Role.BUYER, Role.ADMIN);
        asAdmin = tester(admin);

        category = new Category();
        category.setName("Guides");
        category.setSlug("guides-" + System.nanoTime());
        category = categoryRepository.save(category);
    }

    // ═══ 3. adminUsers search/filter/pagination, bounded query count ═══════════

    private static final String ADMIN_USERS = """
            query($filter: AdminUserFilter, $page: Int, $size: Int) {
              adminUsers(filter: $filter, page: $page, size: $size) {
                content { id email displayName accountStatus roles productCount purchaseCount }
                totalElements totalPages pageNumber
              }
            }
            """;

    @Test
    void search_matchesEmailOrDisplayName_caseInsensitive() {
        makeUser("dragonwriter@example.com", "Rowan Dragonsmith", Role.BUYER);
        makeUser("someoneelse@example.com", "Some One Else", Role.BUYER);

        List<String> emails = asAdmin.document(ADMIN_USERS)
                .variable("filter", Map.of("search", "DRAGON")).variable("page", 0).variable("size", 20)
                .execute().path("adminUsers.content[*].email").entityList(String.class).get();

        assertThat(emails).containsExactly("dragonwriter@example.com");
    }

    @Test
    void filter_byRoleAndStatus() {
        User creator = makeUser("creator1@example.com", "Casey Creator", Role.BUYER, Role.CREATOR);
        User suspended = makeUser("susp@example.com", "Sam Suspended", Role.BUYER);
        suspended.setAccountStatus(AccountStatus.SUSPENDED);
        userRepository.save(suspended);

        List<String> creatorEmails = asAdmin.document(ADMIN_USERS)
                .variable("filter", Map.of("role", "CREATOR")).variable("page", 0).variable("size", 20)
                .execute().path("adminUsers.content[*].email").entityList(String.class).get();
        assertThat(creatorEmails).contains(creator.getEmail()).doesNotContain(suspended.getEmail());

        List<String> suspendedEmails = asAdmin.document(ADMIN_USERS)
                .variable("filter", Map.of("status", "SUSPENDED")).variable("page", 0).variable("size", 20)
                .execute().path("adminUsers.content[*].email").entityList(String.class).get();
        assertThat(suspendedEmails).containsExactly(suspended.getEmail());
    }

    @Test
    void pagination_returnsCorrectMetadata() {
        for (int i = 0; i < 5; i++) {
            makeUser("page-user-" + i + "@example.com", "Page User " + i, Role.BUYER);
        }
        // + admin itself = 6 users total.

        var response = asAdmin.document(ADMIN_USERS)
                .variable("filter", null).variable("page", 0).variable("size", 4)
                .execute();

        assertThat(response.path("adminUsers.totalElements").entity(Integer.class).get()).isEqualTo(6);
        assertThat(response.path("adminUsers.totalPages").entity(Integer.class).get()).isEqualTo(2);
        assertThat(response.path("adminUsers.content[*].id").entityList(String.class).get()).hasSize(4);
    }

    @Test
    void productCountAndPurchaseCount_areCorrect() {
        User creator = makeUser("creator2@example.com", "Cora Creator", Role.BUYER, Role.CREATOR);
        makeLiveProduct(creator, "Book One");
        makeLiveProduct(creator, "Book Two");

        User buyer = makeUser("buyer2@example.com", "Bo Buyer", Role.BUYER);
        Product p1 = makeLiveProduct(creator, "Book Three");
        Product p2 = makeLiveProduct(creator, "Book Four");
        grantEntitlement(buyer, p1);
        grantEntitlement(buyer, p2);

        var response = asAdmin.document(ADMIN_USERS)
                .variable("filter", Map.of("search", creator.getEmail())).variable("page", 0).variable("size", 20)
                .execute();
        assertThat(response.path("adminUsers.content[0].productCount").entity(Integer.class).get()).isEqualTo(4);

        var buyerResponse = asAdmin.document(ADMIN_USERS)
                .variable("filter", Map.of("search", buyer.getEmail())).variable("page", 0).variable("size", 20)
                .execute();
        assertThat(buyerResponse.path("adminUsers.content[0].purchaseCount").entity(Integer.class).get()).isEqualTo(2);
    }

    @Test
    void adminUsersQuery_executesExactlySixStatements_regardlessOfPageSize() {
        for (int i = 0; i < 10; i++) {
            makeUser("bulk-user-" + i + "@example.com", "Bulk User " + i, Role.BUYER);
        }

        SessionFactory sessionFactory = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class);
        Statistics stats = sessionFactory.getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();

        asAdmin.document(ADMIN_USERS).variable("filter", null).variable("page", 0).variable("size", 20).execute();

        // 0: JwtAuthenticationFilter authenticating the calling admin (findWithRolesById) — every
        // authenticated request pays this, not specific to adminUsers. 1: id page (native,
        // LIMIT/OFFSET). 2: COUNT(*). 3: @EntityGraph fetch of the users + roles. 4: productCount
        // GROUP BY. 5: purchaseCount GROUP BY. Six total, always, never N+1 on the page size.
        assertThat(stats.getPrepareStatementCount()).isEqualTo(6);
    }

    // ═══ 4. Suspension is immediate ═════════════════════════════════════════

    private static final String SUSPEND = """
            mutation($id: ID!, $reason: String!) { suspendUser(userId: $id, reason: $reason) { id accountStatus } }
            """;
    private static final String REACTIVATE = """
            mutation($id: ID!) { reactivateUser(userId: $id) { id accountStatus } }
            """;
    private static final String MY_LIBRARY = """
            query { myLibrary { product { id } } }
            """;
    private static final String REFRESH = """
            mutation($token: String!) { refreshToken(token: $token) { accessToken } }
            """;
    private static final String START_SESSION = """
            mutation($productId: ID!, $fp: String!) {
              startViewerSession(productId: $productId, deviceFingerprint: $fp) { sessionId sessionToken }
            }
            """;
    private static final String PAGE_URL = """
            query($token: String!, $page: Int!) { viewerPageUrl(sessionToken: $token, pageNumber: $page) { url } }
            """;

    @Test
    void suspension_isImmediate_andReactivationAllowsFreshLogin() throws Exception {
        authService.register("suspendee@example.com", "Password123!", "Sue Spendee");
        AuthPayload payload = authService.login("suspendee@example.com", "Password123!");
        User target = userRepository.findByEmail("suspendee@example.com").orElseThrow();
        String preSuspensionAccessToken = payload.accessToken();
        String refreshToken = payload.refreshToken();

        Product product = makeLiveProductWithPages(target);
        grantEntitlement(target, product);

        WebTestClient.Builder rawClient = WebTestClient.bindToServer().baseUrl("http://localhost:" + port + "/graphql");
        rawClient.defaultHeader("Authorization", "Bearer " + preSuspensionAccessToken);
        HttpGraphQlTester asTarget = HttpGraphQlTester.create(rawClient.build());

        Map<String, Object> session = asTarget.document(START_SESSION)
                .variable("productId", product.getId()).variable("fp", "device-1")
                .execute().path("startViewerSession").entity(Map.class).get();
        String sessionToken = (String) session.get("sessionToken");
        String tileUrl = asTarget.document(PAGE_URL)
                .variable("token", sessionToken).variable("page", 1)
                .execute().path("viewerPageUrl.url").entity(String.class).get();

        // Admin suspends the target.
        asAdmin.document(SUSPEND).variable("id", target.getId()).variable("reason", "policy violation")
                .execute().path("suspendUser.accountStatus").entity(String.class).isEqualTo("SUSPENDED");

        // (a) the old access token's next GraphQL request is rejected — the JWT filter no
        // longer authenticates it, so the request proceeds as anonymous and isAuthenticated()
        // fails (same shape as CommerceIT's anonymous_cannotBuy: any non-null error code proves
        // rejection, not any one specific code).
        asTarget.document(MY_LIBRARY).execute()
                .errors().expect(e -> e.getExtensions().get("code") != null).verify();

        // (b) refresh fails. suspendUser already revoked every refresh token (D4's first bullet),
        // so AuthService's reuse-detection rejects this one as TOKEN_REUSE before it ever reaches
        // the account_status check — an even more immediate rejection than ACCOUNT_SUSPENDED
        // would be, and exactly what D4 promises ("revoke all refresh tokens").
        asTarget.document(REFRESH).variable("token", refreshToken).execute()
                .errors().expect(e -> "TOKEN_REUSE".equals(e.getExtensions().get("code"))).verify();

        // (c) the viewer session's tile fetch fails.
        mockMvc.perform(get(tileUrl).header("Authorization", "Bearer " + preSuspensionAccessToken))
                .andExpect(status().isUnauthorized());

        // (d) after reactivation, a fresh login works.
        asAdmin.document(REACTIVATE).variable("id", target.getId())
                .execute().path("reactivateUser.accountStatus").entity(String.class).isEqualTo("ACTIVE");
        AuthPayload freshLogin = authService.login("suspendee@example.com", "Password123!");
        assertThat(freshLogin.accessToken()).isNotBlank();
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

    private Product makeLiveProduct(User creator, String title) {
        Product p = new Product();
        p.setCreator(creator);
        p.setCategory(category);
        p.setTitle(title);
        p.setDescription("desc");
        p.setSlug(title.toLowerCase().replace(' ', '-') + "-" + System.nanoTime());
        p.setPricePaise(0L);
        p.setStatus(ProductStatus.LIVE);
        return productRepository.save(p);
    }

    private Product makeLiveProductWithPages(User creator) {
        Product product = makeLiveProduct(creator, "Viewer Book " + System.nanoTime());

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
        storage.put("tiles", key, cleanPagePng(), "image/png");
        ContentPage cp = new ContentPage();
        cp.setDocumentVersion(documentVersion);
        cp.setPageNumber(1);
        cp.setBucketName("tiles");
        cp.setMinioObjectKey(key);
        contentPageRepository.save(cp);

        return product;
    }

    private Entitlement grantEntitlement(User buyer, Product product) {
        Order order = new Order();
        order.setBuyer(buyer);
        order.setTotalAmountPaise(0L);
        order.transitionTo(OrderStatus.COMPLETED);
        order = orderRepository.save(order);

        DocumentVersion version = documentVersionRepository.findFirstByProductIdOrderByVersionNumberDesc(product.getId())
                .orElseGet(() -> {
                    DocumentVersion v = new DocumentVersion();
                    v.setProduct(product);
                    v.setVersionNumber(1);
                    v.setOriginalFilename("book.pdf");
                    v.setFileSizeBytes(1024L);
                    v.setRawMinioBucket("secureleaf-raw");
                    v.setRawMinioObjectKey("raw/" + product.getId() + ".pdf");
                    return documentVersionRepository.save(v);
                });

        Entitlement e = new Entitlement();
        e.setBuyer(buyer);
        e.setProduct(product);
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
