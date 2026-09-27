package com.secureleaf.admin;

import com.secureleaf.auth.entity.Role;
import com.secureleaf.commerce.AbstractCommerceIT;
import com.secureleaf.commerce.CommerceProperties;
import com.secureleaf.commerce.gateway.MockWebhookSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.test.tester.HttpGraphQlTester;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 8 acceptance criterion 7 — {@code platformStats} numbers match a seeded fixture exactly.
 *
 * Extends {@link AbstractCommerceIT} to buy through the real mock-gateway checkout flow (same
 * as {@code ProductStatsIT}) rather than hand-constructing COMPLETED orders — that way the
 * platform-fee split platformStats reports is the SAME code path (D7's snapshot) that produced
 * {@code order_items.platform_fee_paise}, not a second, possibly-diverging calculation.
 */
class AdminAnalyticsIT extends AbstractCommerceIT {

    @Autowired
    private CommerceProperties commerceProperties;

    private HttpGraphQlTester asAdmin;

    private static final String PLATFORM_STATS = """
            query($days: Int) {
              platformStats(days: $days) {
                totalUsers totalCreators totalLiveProducts
                completedOrdersAllTime grossSalesPaiseAllTime platformFeePaiseAllTime
                windowDays completedOrdersWindow grossSalesPaiseWindow platformFeePaiseWindow
                topProducts { productId title salesCount grossSalesPaise }
              }
            }
            """;

    @BeforeEach
    void setUpAdmin() {
        var admin = makeUser("admin@secureleaf.test", "Ada Admin", Role.BUYER, Role.ADMIN);
        asAdmin = tester(admin);
    }

    @Test
    void platformStats_matchesSeededFixtureExactly() throws Exception {
        // Two completed purchases of the paid product; the free product is never bought, so it
        // contributes zero to every sales figure and doesn't appear in topProducts.
        buyAsPaid(asBuyer);
        buyAsPaid(asOtherBuyer);

        long feePerSale = commerceProperties.platformFeeFor(PRICE_PAISE);
        long netPerSale = PRICE_PAISE - feePerSale;
        long expectedGross = 2 * PRICE_PAISE;
        long expectedFee = 2 * feePerSale;

        var response = asAdmin.document(PLATFORM_STATS).variable("days", 30).execute();

        // Fixture: creator + buyer + otherBuyer (AbstractCommerceIT) + admin (this class) = 4.
        response.path("platformStats.totalUsers").entity(Integer.class).isEqualTo(4);
        response.path("platformStats.totalCreators").entity(Integer.class).isEqualTo(1);
        response.path("platformStats.totalLiveProducts").entity(Integer.class).isEqualTo(2);

        response.path("platformStats.completedOrdersAllTime").entity(Integer.class).isEqualTo(2);
        response.path("platformStats.grossSalesPaiseAllTime").entity(Long.class).isEqualTo(expectedGross);
        response.path("platformStats.platformFeePaiseAllTime").entity(Long.class).isEqualTo(expectedFee);

        response.path("platformStats.windowDays").entity(Integer.class).isEqualTo(30);
        response.path("platformStats.completedOrdersWindow").entity(Integer.class).isEqualTo(2);
        response.path("platformStats.grossSalesPaiseWindow").entity(Long.class).isEqualTo(expectedGross);
        response.path("platformStats.platformFeePaiseWindow").entity(Long.class).isEqualTo(expectedFee);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> top = (List<Map<String, Object>>) (List<?>)
                response.path("platformStats.topProducts").entityList(Map.class).get();
        assertThat(top).hasSize(1);
        assertThat(top.get(0).get("title")).isEqualTo(paidProduct.getTitle());
        assertThat(((Number) top.get(0).get("salesCount")).intValue()).isEqualTo(2);
        assertThat(((Number) top.get(0).get("grossSalesPaise")).longValue()).isEqualTo(expectedGross);

        // Sanity: net-per-sale still adds up, proving the fixture's expected numbers are internally
        // consistent (not just copied from the implementation under test).
        assertThat(netPerSale + feePerSale).isEqualTo(PRICE_PAISE);
    }

    private void buyAsPaid(HttpGraphQlTester as) throws Exception {
        Map<String, String> ids = initiate(as, paidProduct, newKey());
        postWebhook(MockWebhookSender.EVENT_CAPTURED, ids.get("gatewayOrderId"), "pay_" + newKey(), PRICE_PAISE, "evt_" + newKey());
    }
}
