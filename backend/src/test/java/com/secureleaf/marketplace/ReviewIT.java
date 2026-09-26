package com.secureleaf.marketplace;

import com.secureleaf.commerce.AbstractCommerceIT;
import com.secureleaf.marketplace.entity.Product;
import com.secureleaf.marketplace.service.ReviewService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.test.tester.HttpGraphQlTester;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REV-01..04, MARKET-03 — reviews, ratings, the concurrency-safe aggregate, and the
 * reviewer-email privacy fix. Reuses {@link AbstractCommerceIT}'s creator/buyer/otherBuyer
 * fixtures and its {@code buy()}-shaped helpers (see LibraryIT) for granting entitlements.
 */
class ReviewIT extends AbstractCommerceIT {

    @Autowired
    private ReviewService reviewService;

    private static final String SUBMIT_REVIEW = """
            mutation($input: SubmitReviewInput!) {
              submitReview(input: $input) {
                id rating reviewText updatedAt
                reviewer { displayName }
              }
            }
            """;

    private static final String DELETE_MY_REVIEW = """
            mutation($productId: ID!) { deleteMyReview(productId: $productId) }
            """;

    private static final String PRODUCT_REVIEWS = """
            query($productId: ID!, $page: Int, $size: Int) {
              productReviews(productId: $productId, page: $page, size: $size) {
                content { id rating reviewText reviewer { displayName } }
                totalElements totalPages pageNumber
              }
            }
            """;

    private static final String MY_REVIEW = """
            query($productId: ID!) { myReview(productId: $productId) { id rating reviewText } }
            """;

    private static final String RATING_BREAKDOWN = """
            query($productId: ID!) { ratingBreakdown(productId: $productId) { rating count } }
            """;

    // ── D1 — who can review ─────────────────────────────────────────────────

    @Test
    void nonEntitledBuyer_isRejected() {
        asOtherBuyer.document(SUBMIT_REVIEW)
                .variable("input", Map.of("productId", paidProduct.getId(), "rating", 5))
                .execute().errors().expect(e -> "NOT_ENTITLED".equals(e.getExtensions().get("code"))).verify();
    }

    @Test
    void creator_cannotReviewTheirOwnProduct() {
        asCreator.document(SUBMIT_REVIEW)
                .variable("input", Map.of("productId", paidProduct.getId(), "rating", 5))
                .execute().errors().expect(e -> "ACCESS_DENIED".equals(e.getExtensions().get("code"))).verify();
    }

    // ── D2 — upsert, D4 — aggregate stays correct ────────────────────────────

    @Test
    void submitReview_isAnUpsert_andRecomputesTheAggregate() {
        buy(asOtherBuyer, freeProduct);
        buy(asBuyer, freeProduct);

        asBuyer.document(SUBMIT_REVIEW)
                .variable("input", Map.of("productId", freeProduct.getId(), "rating", 4, "reviewText", "Good"))
                .execute().path("submitReview.rating").entity(Integer.class).isEqualTo(4);

        assertThat(reviewCount(freeProduct)).isEqualTo(1);
        assertThat(averageRating(freeProduct)).isEqualByComparingTo("4.00");

        // A second submitReview from the SAME buyer edits the existing row, not a new one.
        asBuyer.document(SUBMIT_REVIEW)
                .variable("input", Map.of("productId", freeProduct.getId(), "rating", 2, "reviewText", "Actually, meh"))
                .execute().path("submitReview.rating").entity(Integer.class).isEqualTo(2);

        assertThat(reviewCount(freeProduct)).isEqualTo(1);
        assertThat(averageRating(freeProduct)).isEqualByComparingTo("2.00");

        // A different buyer's review is a second row, and the average reflects both.
        asOtherBuyer.document(SUBMIT_REVIEW)
                .variable("input", Map.of("productId", freeProduct.getId(), "rating", 4))
                .execute().path("submitReview.rating").entity(Integer.class).isEqualTo(4);

        assertThat(reviewCount(freeProduct)).isEqualTo(2);
        assertThat(averageRating(freeProduct)).isEqualByComparingTo("3.00");

        // Delete recomputes back down; when the last review goes, the average returns to NULL.
        asOtherBuyer.document(DELETE_MY_REVIEW).variable("productId", freeProduct.getId())
                .execute().path("deleteMyReview").entity(Boolean.class).isEqualTo(true);
        assertThat(reviewCount(freeProduct)).isEqualTo(1);

        asBuyer.document(DELETE_MY_REVIEW).variable("productId", freeProduct.getId())
                .execute().path("deleteMyReview").entity(Boolean.class).isEqualTo(true);
        assertThat(reviewCount(freeProduct)).isEqualTo(0);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT average_rating FROM products WHERE id = ?", BigDecimal.class, freeProduct.getId())).isNull();
    }

    @Test
    void deleteMyReview_withNoExistingReview_returnsFalse() {
        buy(asBuyer, freeProduct);
        asBuyer.document(DELETE_MY_REVIEW).variable("productId", freeProduct.getId())
                .execute().path("deleteMyReview").entity(Boolean.class).isEqualTo(false);
    }

    // ── D4 — concurrency: two buyers reviewing the same product at once ─────

    @Test
    void concurrentReviewsFromDifferentBuyers_bothLand_andAverageIsExact() throws Exception {
        buy(asBuyer, freeProduct);
        buy(asOtherBuyer, freeProduct);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var f1 = pool.submit(() -> {
                ready.countDown();
                await(go);
                reviewService.submitReview(buyer.getId(), freeProduct.getId(), 5, "Five stars");
            });
            var f2 = pool.submit(() -> {
                ready.countDown();
                await(go);
                reviewService.submitReview(otherBuyer.getId(), freeProduct.getId(), 3, "Three stars");
            });
            ready.await(5, TimeUnit.SECONDS);
            go.countDown();
            f1.get(10, TimeUnit.SECONDS);
            f2.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdown();
        }

        assertThat(reviewCount(freeProduct)).isEqualTo(2);
        assertThat(averageRating(freeProduct)).isEqualByComparingTo("4.00"); // (5 + 3) / 2, never a lost update
    }

    // ── D5 — the reviewer-email privacy fix ──────────────────────────────────

    @Test
    void reviewerSummary_hasNoEmailField() {
        buy(asBuyer, freeProduct);
        asBuyer.document(SUBMIT_REVIEW)
                .variable("input", Map.of("productId", freeProduct.getId(), "rating", 5))
                .execute();

        asBuyer.document("""
                query($productId: ID!) {
                  productReviews(productId: $productId) { content { reviewer { displayName email } } }
                }
                """)
                .variable("productId", freeProduct.getId())
                .execute()
                .errors()
                .expect(e -> e.getMessage().toLowerCase().contains("email"))
                .verify();
    }

    // ── D6 — queries ──────────────────────────────────────────────────────────

    @Test
    void productReviews_paginates_newestFirst() {
        buy(asBuyer, freeProduct);
        buy(asOtherBuyer, freeProduct);
        asBuyer.document(SUBMIT_REVIEW)
                .variable("input", Map.of("productId", freeProduct.getId(), "rating", 4)).execute();
        asOtherBuyer.document(SUBMIT_REVIEW)
                .variable("input", Map.of("productId", freeProduct.getId(), "rating", 2)).execute();

        var page = asBuyer.document(PRODUCT_REVIEWS)
                .variable("productId", freeProduct.getId()).variable("page", 0).variable("size", 1)
                .execute();
        page.path("productReviews.totalElements").entity(Integer.class).isEqualTo(2);
        page.path("productReviews.totalPages").entity(Integer.class).isEqualTo(2);
        page.path("productReviews.content[*].rating").entityList(Integer.class).isEqualTo(List.of(2)); // newest first
    }

    @Test
    void myReview_reflectsTheCallersOwnReview_andNothingElse() {
        buy(asBuyer, freeProduct);
        buy(asOtherBuyer, freeProduct);
        asBuyer.document(SUBMIT_REVIEW)
                .variable("input", Map.of("productId", freeProduct.getId(), "rating", 4)).execute();

        asBuyer.document(MY_REVIEW).variable("productId", freeProduct.getId())
                .execute().path("myReview.rating").entity(Integer.class).isEqualTo(4);
        asOtherBuyer.document(MY_REVIEW).variable("productId", freeProduct.getId())
                .execute().path("myReview").valueIsNull();
    }

    @Test
    void ratingBreakdown_alwaysReturnsAllFiveStars() {
        buy(asBuyer, freeProduct);
        asBuyer.document(SUBMIT_REVIEW)
                .variable("input", Map.of("productId", freeProduct.getId(), "rating", 5)).execute();

        var breakdown = asBuyer.document(RATING_BREAKDOWN).variable("productId", freeProduct.getId()).execute();
        breakdown.path("ratingBreakdown[*].rating").entityList(Integer.class).isEqualTo(List.of(5, 4, 3, 2, 1));
        breakdown.path("ratingBreakdown[*].count").entityList(Integer.class).isEqualTo(List.of(1, 0, 0, 0, 0));
    }

    // ── D7 / MARKET-03 — marketplace rating filter, unrated sorts last ───────

    @Test
    void minRatingFilter_excludesLowerRatedAndUnratedProducts_andRatingSortPutsUnratedLast() {
        buy(asBuyer, freeProduct);
        asBuyer.document(SUBMIT_REVIEW)
                .variable("input", Map.of("productId", freeProduct.getId(), "rating", 5)).execute();
        // paidProduct has no reviews — average_rating stays NULL.

        var filtered = asBuyer.document("""
                query($filter: ProductFilterInput) {
                  products(filter: $filter) { content { id } totalElements }
                }
                """).variable("filter", Map.of("minRating", 4.0)).execute();
        filtered.path("products.content[*].id").entityList(String.class)
                .isEqualTo(List.of(String.valueOf(freeProduct.getId())));

        var sorted = asBuyer.document("""
                query($filter: ProductFilterInput) {
                  products(filter: $filter) { content { id averageRating } }
                }
                """).variable("filter", Map.of("sortBy", "rating")).execute();
        sorted.path("products.content[*].id").entityList(String.class)
                .isEqualTo(List.of(String.valueOf(freeProduct.getId()), String.valueOf(paidProduct.getId())));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void buy(HttpGraphQlTester as, Product product) {
        as.document(INITIATE).variable("productId", product.getId()).variable("key", newKey()).execute();
    }

    private int reviewCount(Product product) {
        return count("SELECT review_count FROM products WHERE id = ?", product.getId());
    }

    private BigDecimal averageRating(Product product) {
        return jdbcTemplate.queryForObject("SELECT average_rating FROM products WHERE id = ?", BigDecimal.class, product.getId());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
