package com.secureleaf.marketplace;

import com.secureleaf.AbstractIntegrationTest;
import com.secureleaf.auth.entity.User;
import com.secureleaf.auth.repository.UserRepository;
import com.secureleaf.marketplace.entity.Category;
import com.secureleaf.marketplace.entity.Product;
import com.secureleaf.marketplace.entity.ProductStatus;
import com.secureleaf.marketplace.entity.ProductTag;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Public, unauthenticated marketplace queries — HttpGraphQlTester never sends an
 * Authorization header, proving `products`/`product` genuinely don't require auth
 * (see D4/SecurityConfig: /graphql is permitAll, and these resolvers carry no
 * @PreAuthorize).
 */
class MarketplaceQueryIT extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @PersistenceContext
    private EntityManager entityManager;

    private HttpGraphQlTester graphQlTester;
    private User creator;
    private Category category;

    @BeforeEach
    void setUp() {
        productRepository.deleteAll();
        categoryRepository.deleteAll();
        userRepository.deleteAll();

        creator = new User();
        creator.setEmail("creator@example.com");
        creator.setDisplayName("Jane Creator");
        creator.setPasswordHash("hash");
        creator = userRepository.save(creator);

        category = new Category();
        category.setName("Fiction");
        category.setSlug("fiction");
        category = categoryRepository.save(category);

        WebTestClient client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port + "/graphql")
                .build();
        graphQlTester = HttpGraphQlTester.create(client);
    }

    private Product makeProduct(String title, String description, ProductStatus status,
                                 long pricePaise, boolean deleted) {
        Product p = new Product();
        p.setCreator(creator);
        p.setCategory(category);
        p.setTitle(title);
        p.setDescription(description);
        p.setSlug(title.toLowerCase().replace(" ", "-") + "-" + System.nanoTime());
        p.setPricePaise(pricePaise);
        p.setStatus(status);
        if (deleted) p.setDeletedAt(Instant.now());
        Product saved = productRepository.saveAndFlush(p);
        ProductTag tag = new ProductTag();
        tag.setProduct(saved);
        tag.setTag("adventure");
        saved.getTags().add(tag);
        return productRepository.save(saved);
    }

    // ── Visibility rules (D4) ───────────────────────────────────────────────

    @Test
    void productsQuery_excludesNonLiveAndDeleted() {
        makeProduct("Live Book", "a great adventure story", ProductStatus.LIVE, 1000, false);
        makeProduct("Draft Book", "a draft adventure story", ProductStatus.DRAFT, 1000, false);
        makeProduct("Unpublished Book", "an unpublished adventure story", ProductStatus.UNPUBLISHED, 1000, false);
        makeProduct("Deleted Book", "a deleted adventure story", ProductStatus.LIVE, 1000, true);

        List<String> titles = graphQlTester.document("""
                query { products(page: 0, size: 20) { content { title } totalElements } }
                """)
                .execute()
                .path("products.content[*].title")
                .entityList(String.class)
                .get();

        assertThat(titles).containsExactly("Live Book");
    }

    @Test
    void productQuery_onDraftProduct_throwsNotFound_notForbidden() {
        Product draft = makeProduct("Secret Draft", "not visible yet", ProductStatus.DRAFT, 500, false);

        graphQlTester.document("query($id: ID!) { product(id: $id) { id } }")
                .variable("id", draft.getId())
                .execute()
                .errors()
                .expect(err -> err.getExtensions().get("code").equals("NOT_FOUND"))
                .verify()
                .path("product").valueIsNull();
    }

    @Test
    void productQuery_neverLeaksCreatorEmail() {
        // Schema-level guarantee: Product.creator is CreatorSummary, which has no
        // `email` field at all — selecting it is a GraphQL validation error, not a
        // runtime leak. This proves the schema itself makes the leak impossible.
        Product live = makeProduct("Public Book", "an adventure for everyone", ProductStatus.LIVE, 100, false);

        graphQlTester.document("query($id: ID!) { product(id: $id) { creator { email } } }")
                .variable("id", live.getId())
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors).isNotEmpty());
    }

    // ── Full-text search ─────────────────────────────────────────────────────

    @Test
    void search_findsMatchesByTitleOrDescription_excludesNonMatches() {
        makeProduct("Dragons of the North", "an epic fantasy adventure", ProductStatus.LIVE, 100, false);
        makeProduct("Cooking with Herbs", "a culinary guide to spices", ProductStatus.LIVE, 100, false);

        List<String> titles = graphQlTester.document("""
                query($q: String) {
                  products(filter: { searchQuery: $q }, page: 0, size: 20) { content { title } }
                }
                """)
                .variable("q", "dragons fantasy")
                .execute()
                .path("products.content[*].title")
                .entityList(String.class)
                .get();

        assertThat(titles).containsExactly("Dragons of the North");
    }

    @Test
    void search_usesTheFtsIndex() {
        // Seed enough rows that the planner prefers the index over a seq scan.
        for (int i = 0; i < 60; i++) {
            makeProduct("Adventure Tale " + i, "a thrilling adventure story number " + i, ProductStatus.LIVE, 100, false);
        }
        entityManager.flush();

        String plan = String.join("\n", jdbcTemplate.queryForList(
                "EXPLAIN ANALYZE SELECT id FROM products WHERE status = 'LIVE' AND deleted_at IS NULL "
                        + "AND to_tsvector('english', title || ' ' || description) @@ plainto_tsquery('english', 'adventure') "
                        + "ORDER BY created_at DESC LIMIT 20",
                Map.of())
                .stream().map(row -> String.valueOf(row.get("QUERY PLAN"))).toList());

        assertThat(plan).contains("idx_products_fts");
    }

    // ── Filters (AND semantics) ──────────────────────────────────────────────

    @Test
    void filters_combineWithAndSemantics() {
        makeProduct("Cheap Fiction Book", "an adventure story", ProductStatus.LIVE, 0, false);
        makeProduct("Expensive Fiction Book", "an adventure story", ProductStatus.LIVE, 5000, false);

        List<String> titles = graphQlTester.document("""
                query($slug: String) {
                  products(filter: { categorySlug: $slug, isFree: true }, page: 0, size: 20) { content { title } }
                }
                """)
                .variable("slug", category.getSlug())
                .execute()
                .path("products.content[*].title")
                .entityList(String.class)
                .get();

        assertThat(titles).containsExactly("Cheap Fiction Book");
    }

    // ── Pagination ────────────────────────────────────────────────────────────

    @Test
    void pagination_returnsCorrectPageMetadata() {
        for (int i = 0; i < 25; i++) {
            makeProduct("Book " + i, "an adventure story " + i, ProductStatus.LIVE, 100, false);
        }

        var response = graphQlTester.document("""
                query { products(page: 1, size: 10) { totalElements totalPages pageNumber content { id } } }
                """)
                .execute();

        assertThat(response.path("products.totalElements").entity(Integer.class).get()).isEqualTo(25);
        assertThat(response.path("products.totalPages").entity(Integer.class).get()).isEqualTo(3);
        assertThat(response.path("products.pageNumber").entity(Integer.class).get()).isEqualTo(1);
        assertThat(response.path("products.content[*].id").entityList(String.class).get()).hasSize(10);
    }

    @Test
    void pagination_lastPageHasRemainder() {
        for (int i = 0; i < 25; i++) {
            makeProduct("Book " + i, "an adventure story " + i, ProductStatus.LIVE, 100, false);
        }

        var response = graphQlTester.document("""
                query { products(page: 2, size: 10) { content { id } } }
                """)
                .execute();

        assertThat(response.path("products.content[*].id").entityList(String.class).get()).hasSize(5);
    }

    // ── N+1 assertion (flagship teaching point, D2) ──────────────────────────

    @Test
    void listingRequest_executesExactlyThreeStatements_regardlessOfPageSize() {
        for (int i = 0; i < 20; i++) {
            makeProduct("Book " + i, "an adventure story " + i, ProductStatus.LIVE, 100, false);
        }

        SessionFactory sessionFactory = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class);
        Statistics stats = sessionFactory.getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();

        graphQlTester.document("""
                query { products(page: 0, size: 20) { content { id tags } } }
                """)
                .execute();

        // 1: SELECT ids (native, LIMIT/OFFSET). 2: COUNT(*). 3: the @EntityGraph
        // fetch of the 20 products + their tags. If HHH000104 fired instead, this
        // would be dramatically higher because Hibernate paginates in memory after
        // loading every LIVE product's tags collection.
        assertThat(stats.getPrepareStatementCount()).isEqualTo(3);
    }

    // ── Ordering ──────────────────────────────────────────────────────────────

    @Test
    void sortBy_priceAsc_ordersAscending() {
        makeProduct("Mid", "an adventure story", ProductStatus.LIVE, 500, false);
        makeProduct("Cheap", "an adventure story", ProductStatus.LIVE, 100, false);
        makeProduct("Pricey", "an adventure story", ProductStatus.LIVE, 900, false);

        List<String> titles = graphQlTester.document("""
                query { products(filter: { sortBy: "price_asc" }, page: 0, size: 20) { content { title } } }
                """)
                .execute()
                .path("products.content[*].title")
                .entityList(String.class)
                .get();

        assertThat(titles).containsExactly("Cheap", "Mid", "Pricey");
    }

    @Test
    void categoriesQuery_returnsSeededCategories() {
        graphQlTester.document("query { categories { slug } }")
                .execute()
                .path("categories[*].slug")
                .entityList(String.class)
                .get()
                .contains(category.getSlug());
    }
}
