package com.secureleaf.commerce;

import com.secureleaf.commerce.gateway.MockWebhookSender;
import com.secureleaf.commerce.repository.OrderItemRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.graphql.test.tester.HttpGraphQlTester;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Phase 6, D2 — {@code Product.salesCount}/{@code netEarningsPaise}: correct after real
 * purchases, creator-only, and both fields share one aggregate query per request.
 */
class ProductStatsIT extends AbstractCommerceIT {

    @Autowired
    private CommerceProperties commerceProperties;

    /**
     * Spied, not mocked: real behaviour is kept (the aggregate query still runs and returns real
     * numbers) — spying only adds the ability to verify how many times it ran. Counting THIS
     * specific repository call, instead of the session's total JDBC statement count, is what
     * makes the third test immune to unrelated background @Scheduled queries (the job poller,
     * the viewer-session sweeper) that would otherwise make a global statement-count assertion
     * flaky under a long test run.
     */
    @SpyBean
    private OrderItemRepository orderItemRepository;

    private static final String MY_PRODUCTS_STATS = """
            query { myProducts { id title salesCount netEarningsPaise } }
            """;

    private static final String PUBLIC_PRODUCT_STATS = """
            query($id: ID!) { product(id: $id) { id salesCount netEarningsPaise } }
            """;

    @Test
    void salesCountAndNetEarnings_areCorrectAfterTwoPurchases() throws Exception {
        buyAsPaid(asBuyer);
        buyAsPaid(asOtherBuyer);

        long expectedFeePerSale = commerceProperties.platformFeeFor(PRICE_PAISE);
        long expectedNetPerSale = PRICE_PAISE - expectedFeePerSale;

        var response = asCreator.document(MY_PRODUCTS_STATS).execute();
        List<String> titles = response.path("myProducts[*].title").entityList(String.class).get();
        int paidIndex = titles.indexOf("Paid Guide");
        int freeIndex = titles.indexOf("Free Guide");

        response.path("myProducts[%d].salesCount".formatted(paidIndex)).entity(Integer.class).isEqualTo(2);
        response.path("myProducts[%d].netEarningsPaise".formatted(paidIndex))
                .entity(Long.class).isEqualTo(2 * expectedNetPerSale);
        // No completed sales at all — zero, not null, for the OWNER.
        response.path("myProducts[%d].salesCount".formatted(freeIndex)).entity(Integer.class).isEqualTo(0);
    }

    @Test
    void stats_areNullForANonOwner_onTheSamePublicProductQuery() throws Exception {
        buyAsPaid(asBuyer);

        var asBuyerView = asBuyer.document(PUBLIC_PRODUCT_STATS).variable("id", paidProduct.getId()).execute();
        asBuyerView.path("product.salesCount").valueIsNull();
        asBuyerView.path("product.netEarningsPaise").valueIsNull();

        var anonymousView = anonymous.document(PUBLIC_PRODUCT_STATS).variable("id", paidProduct.getId()).execute();
        anonymousView.path("product.salesCount").valueIsNull();

        // The owner, asking the exact same public query, sees the real number.
        var asCreatorView = asCreator.document(PUBLIC_PRODUCT_STATS).variable("id", paidProduct.getId()).execute();
        asCreatorView.path("product.salesCount").entity(Integer.class).isEqualTo(1);
    }

    @Test
    void bothFields_shareOneAggregateQuery_perRequest() throws Exception {
        buyAsPaid(asBuyer);

        asCreator.document(MY_PRODUCTS_STATS).execute();

        // If salesCount and netEarningsPaise each ran their own @BatchMapping query, this single
        // request (both fields, on every product) would have called the repository twice.
        // Because they share one named DataLoader (ProductStatsLoaderConfig), the aggregate
        // query's underlying repository method runs exactly once no matter how many of the two
        // fields — or how many products — the request asks for.
        verify(orderItemRepository, times(1)).sumStatsByProductIds(any(Collection.class));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void buyAsPaid(HttpGraphQlTester as) throws Exception {
        Map<String, String> ids = initiate(as, paidProduct, newKey());
        postWebhook(MockWebhookSender.EVENT_CAPTURED, ids.get("gatewayOrderId"), "pay_" + newKey(), PRICE_PAISE, "evt_" + newKey());
    }
}
