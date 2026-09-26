package com.secureleaf.commerce;

import com.secureleaf.commerce.gateway.MockWebhookSender;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6, D1 — the buyer's library shows every entitlement it has ever held, not just the
 * currently-usable ones; D5 — unpublishing/deleting a product never takes away what a buyer
 * already paid for.
 */
class LibraryIT extends AbstractCommerceIT {

    @PersistenceContext
    private EntityManager entityManager;

    private static final String MY_LIBRARY = """
            query {
              myLibrary {
                id
                status
                purchasedAt
                product { id title status }
              }
            }
            """;

    private static final String START_VIEWER_SESSION = """
            mutation($productId: ID!) {
              startViewerSession(productId: $productId, deviceFingerprint: "test-device") {
                sessionId
                sessionToken
              }
            }
            """;

    // ── D1 — every status shows up ───────────────────────────────────────────

    @Test
    void myLibrary_includesRevokedEntitlements_withTheCorrectStatus() {
        buy(paidProduct);
        String entitlementId = asBuyer.document(MY_LIBRARY).execute()
                .path("myLibrary[0].id").entity(String.class).get();

        jdbcTemplate.update("UPDATE entitlements SET status = 'REVOKED', revoked_at = now() WHERE id = ?",
                Long.valueOf(entitlementId));

        List<String> statuses = asBuyer.document(MY_LIBRARY).execute()
                .path("myLibrary[*].status").entityList(String.class).get();
        assertThat(statuses).containsExactly("REVOKED");
    }

    @Test
    void myLibrary_isNewestFirst_andQueryCountDoesNotGrowWithLibrarySize() {
        buy(freeProduct);

        SessionFactory sessionFactory = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class);
        Statistics stats = sessionFactory.getStatistics();
        stats.setStatisticsEnabled(true);

        stats.clear();
        List<String> oneItem = asBuyer.document(MY_LIBRARY).execute()
                .path("myLibrary[*].product.title").entityList(String.class).get();
        long statementsForOne = stats.getPrepareStatementCount();

        buy(paidProduct);

        stats.clear();
        List<String> twoItems = asBuyer.document(MY_LIBRARY).execute()
                .path("myLibrary[*].product.title").entityList(String.class).get();
        long statementsForTwo = stats.getPrepareStatementCount();

        // The flagship point (D1: "no N+1", same teaching moment as MarketplaceQueryIT's
        // listingRequest_executesExactlyThreeStatements): fetching a 2nd entitlement's product,
        // creator, category and tags must NOT add a 2nd round of queries.
        assertThat(statementsForTwo).isEqualTo(statementsForOne);
        assertThat(oneItem).containsExactly("Free Guide");
        assertThat(twoItems).containsExactly("Paid Guide", "Free Guide");
    }

    // ── D5 — soft delete / unpublish never revoke buyer access ───────────────

    @Test
    void deletedProduct_staysInTheBuyersLibrary_andRemainsViewable() {
        buy(paidProduct);

        asCreator.document("mutation($id: ID!) { deleteProduct(id: $id) }")
                .variable("id", paidProduct.getId()).execute()
                .path("deleteProduct").entity(Boolean.class).isEqualTo(true);

        var response = asBuyer.document(MY_LIBRARY).execute();
        response.path("myLibrary[0].product.id").entity(String.class).isEqualTo(String.valueOf(paidProduct.getId()));

        // Still viewable: startViewerSession only checks for an ACTIVE entitlement, never the
        // product's own status/deleted_at (ViewerSessionService, D1/D2 of the Phase 5 spec).
        asBuyer.document(START_VIEWER_SESSION).variable("productId", paidProduct.getId()).execute()
                .path("startViewerSession.sessionToken").entity(String.class).satisfies(token -> assertThat(token).isNotBlank());
    }

    @Test
    void unpublishedProduct_staysInTheBuyersLibrary_andRemainsViewable() {
        buy(paidProduct);

        asCreator.document("mutation($id: ID!) { unpublishProduct(id: $id) { status } }")
                .variable("id", paidProduct.getId()).execute()
                .path("unpublishProduct.status").entity(String.class).isEqualTo("UNPUBLISHED");

        asBuyer.document(MY_LIBRARY).execute()
                .path("myLibrary[0].product.status").entity(String.class).isEqualTo("UNPUBLISHED");

        asBuyer.document(START_VIEWER_SESSION).variable("productId", paidProduct.getId()).execute()
                .path("startViewerSession.sessionToken").entity(String.class).satisfies(token -> assertThat(token).isNotBlank());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void buy(com.secureleaf.marketplace.entity.Product product) {
        if (product.getPricePaise() == 0) {
            // Free products complete synchronously (D10) — no gatewayOrderId to read back, so
            // this skips the shared initiate() helper (which asserts on that field).
            asBuyer.document(INITIATE).variable("productId", product.getId()).variable("key", newKey()).execute();
            return;
        }
        Map<String, String> ids = initiate(asBuyer, product, newKey());
        postWebhookQuietly(ids.get("gatewayOrderId"));
    }

    private void postWebhookQuietly(String gatewayOrderId) {
        try {
            postWebhook(MockWebhookSender.EVENT_CAPTURED, gatewayOrderId, "pay_" + newKey(), PRICE_PAISE, "evt_" + newKey());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
