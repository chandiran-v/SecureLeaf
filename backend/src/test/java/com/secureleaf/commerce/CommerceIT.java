package com.secureleaf.commerce;

import com.jayway.jsonpath.JsonPath;
import com.secureleaf.commerce.gateway.MockWebhookSender;
import com.secureleaf.commerce.service.PaymentCompletionService;
import com.secureleaf.marketplace.entity.Product;
import com.secureleaf.marketplace.entity.ProductStatus;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 4 — the commerce pipeline end to end: order → mock Razorpay → verify/webhook →
 * entitlement. Each nested class is one of the phase's promises; each test is a way that
 * promise could be broken in production.
 */
class CommerceIT extends AbstractCommerceIT {

    @Autowired
    private PaymentCompletionService paymentCompletionService;

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    class HappyPath {

        @Test
        void buy_payAtGateway_verify_grantsEntitlementAndRecordsEverything() throws Exception {
            Map<String, String> ids = initiate(asBuyer, paidProduct, newKey());
            assertThat(orderStatus(ids.get("orderId"))).isEqualTo("PENDING");

            MvcResult paid = payAtGateway(ids.get("gatewayOrderId"), "SUCCESS")
                    .andExpect(status().isOk()).andReturn();
            String body = paid.getResponse().getContentAsString();

            asBuyer.document(VERIFY)
                    .variable("input", Map.of(
                            "orderId", ids.get("orderId"),
                            "gatewayOrderId", JsonPath.read(body, "$.razorpay_order_id"),
                            "gatewayPaymentId", JsonPath.read(body, "$.razorpay_payment_id"),
                            "gatewaySignature", JsonPath.read(body, "$.razorpay_signature")))
                    .execute()
                    .path("verifyPayment.status").entity(String.class).isEqualTo("COMPLETED");

            assertThat(activeEntitlements(buyer, paidProduct)).isEqualTo(1);
            assertThat(totalSales(paidProduct)).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM payments WHERE status = 'COMPLETED' AND provider_payment_id IS NOT NULL"))
                    .isEqualTo(1);

            // PAY-06 — the audit log tells the whole story, in order.
            List<String> transitions = jdbcTemplate.queryForList("""
                    SELECT coalesce(from_status::text, '∅') || '→' || to_status::text || ' (' || event_source || ')'
                    FROM payment_events ORDER BY id""", String.class);
            assertThat(transitions).containsExactly(
                    "∅→PENDING (CHECKOUT)",
                    "PENDING→COMPLETED (CHECKOUT_CALLBACK)");

            // PAY-10 — 10% of ₹499 = ₹49.90 fee, ₹449.10 to the creator, snapshotted on the item.
            Map<String, Object> split = jdbcTemplate.queryForMap(
                    "SELECT platform_fee_paise, creator_earnings_paise FROM order_items");
            assertThat(split).containsEntry("platform_fee_paise", 4_990L).containsEntry("creator_earnings_paise", 44_910L);
        }

        @Test
        void freeProduct_completesImmediately_withoutAnyPayment() {
            asBuyer.document(INITIATE)
                    .variable("productId", freeProduct.getId()).variable("key", newKey())
                    .execute()
                    .path("initiateOrder.order.status").entity(String.class).isEqualTo("COMPLETED")
                    .path("initiateOrder.gatewayOrderId").valueIsNull()
                    .path("initiateOrder.gatewayKeyId").valueIsNull();

            assertThat(activeEntitlements(buyer, freeProduct)).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM payments")).isZero();   // D10 — no money moved, no payment row
        }

        @Test
        void library_keepsPurchasedProduct_evenAfterCreatorUnpublishesIt() {
            asBuyer.document(INITIATE).variable("productId", freeProduct.getId()).variable("key", newKey())
                    .execute();

            Product p = productRepository.findById(freeProduct.getId()).orElseThrow();
            p.setStatus(ProductStatus.UNPUBLISHED);
            productRepository.save(p);

            asBuyer.document("query { myLibrary { product { id title } status } }")
                    .execute()
                    .path("myLibrary[*].product.title").entityList(String.class).containsExactly("Free Guide");
        }

        @Test
        void creatorEarnings_sumOnlyCompletedOrders() throws Exception {
            Map<String, String> ids = initiate(asBuyer, paidProduct, newKey());
            postWebhook(MockWebhookSender.EVENT_CAPTURED, ids.get("gatewayOrderId"), "pay_A", PRICE_PAISE, "evt_A");
            initiate(asOtherBuyer, paidProduct, newKey());            // pending — must NOT count
            asOtherBuyer.document(INITIATE).variable("productId", freeProduct.getId()).variable("key", newKey())
                    .execute();                                        // free — counts as a ₹0 sale

            asCreator.document("query { creatorEarnings { salesCount grossSalesPaise platformFeePaise netEarningsPaise } }")
                    .execute()
                    .path("creatorEarnings.salesCount").entity(Long.class).isEqualTo(2L)
                    .path("creatorEarnings.grossSalesPaise").entity(Long.class).isEqualTo(49_900L)
                    .path("creatorEarnings.platformFeePaise").entity(Long.class).isEqualTo(4_990L)
                    .path("creatorEarnings.netEarningsPaise").entity(Long.class).isEqualTo(44_910L);
        }

        @Test
        void creatorEarnings_isCreatorOnly() {
            asBuyer.document("query { creatorEarnings { salesCount } }")
                    .execute().errors().expect(e -> "ACCESS_DENIED".equals(e.getExtensions().get("code"))).verify();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    class Idempotency {

        @Test
        void sameKey_returnsSameOrder_andCreatesOnlyOne() {
            String key = newKey();
            String first = asBuyer.document(INITIATE).variable("productId", paidProduct.getId()).variable("key", key)
                    .execute().path("initiateOrder.order.id").entity(String.class).get();
            String second = asBuyer.document(INITIATE).variable("productId", paidProduct.getId()).variable("key", key)
                    .execute().path("initiateOrder.order.id").entity(String.class).get();

            assertThat(second).isEqualTo(first);
            assertThat(count("SELECT count(*) FROM orders")).isEqualTo(1);
        }

        @Test
        void differentKey_sameProduct_reusesTheOpenOrder() {
            String first = initiate(asBuyer, paidProduct, newKey()).get("orderId");
            String second = initiate(asBuyer, paidProduct, newKey()).get("orderId");   // "second tab"

            assertThat(second).isEqualTo(first);
            assertThat(count("SELECT count(*) FROM orders")).isEqualTo(1);
        }

        @Test
        void sameKey_forADifferentProduct_isRejected() {
            String key = newKey();
            initiate(asBuyer, paidProduct, key);

            asBuyer.document(INITIATE).variable("productId", freeProduct.getId()).variable("key", key)
                    .execute().errors()
                    .expect(e -> "IDEMPOTENCY_KEY_REUSED".equals(e.getExtensions().get("code"))).verify();
        }

        @Test
        void priceChange_supersedesTheStaleOpenOrder() {
            String stale = initiate(asBuyer, paidProduct, newKey()).get("orderId");
            Product p = productRepository.findById(paidProduct.getId()).orElseThrow();
            p.setPricePaise(59_900L);
            productRepository.save(p);

            String fresh = initiate(asBuyer, paidProduct, newKey()).get("orderId");

            assertThat(fresh).isNotEqualTo(stale);
            assertThat(orderStatus(stale)).isEqualTo("FAILED");
            assertThat(orderStatus(fresh)).isEqualTo("PENDING");
        }

        @Test
        void concurrentInitiates_fromOneBuyer_allGetTheSameOrder() throws Exception {
            // Ten "double-clicks" at once, each with its own key. Without the per-buyer row lock
            // (D2 layer 3), several would pass the "no open order yet" check simultaneously.
            List<String> orderIds = runConcurrently(10, () -> asBuyer.document(INITIATE)
                    .variable("productId", paidProduct.getId()).variable("key", newKey())
                    .execute().path("initiateOrder.order.id").entity(String.class).get());

            assertThat(Set.copyOf(orderIds)).hasSize(1);
            assertThat(count("SELECT count(*) FROM orders")).isEqualTo(1);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    class Webhooks {

        @Test
        void capturedWebhook_alone_completesTheOrder() throws Exception {
            // D13 — the browser never reported back (closed tab / timeout). The webhook suffices.
            Map<String, String> ids = initiate(asBuyer, paidProduct, newKey());

            postWebhook(MockWebhookSender.EVENT_CAPTURED, ids.get("gatewayOrderId"), "pay_1", PRICE_PAISE, "evt_1")
                    .andExpect(status().isOk());

            assertThat(orderStatus(ids.get("orderId"))).isEqualTo("COMPLETED");
            assertThat(activeEntitlements(buyer, paidProduct)).isEqualTo(1);
        }

        @Test
        void duplicateDelivery_isProcessedOnce() throws Exception {
            Map<String, String> ids = initiate(asBuyer, paidProduct, newKey());

            for (int i = 0; i < 3; i++) {
                postWebhook(MockWebhookSender.EVENT_CAPTURED, ids.get("gatewayOrderId"), "pay_1", PRICE_PAISE, "evt_same")
                        .andExpect(status().isOk());   // 2xx every time, so the provider stops retrying
            }

            assertThat(activeEntitlements(buyer, paidProduct)).isEqualTo(1);
            assertThat(totalSales(paidProduct)).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM payment_events WHERE provider_event_id = 'evt_same'")).isEqualTo(1);
        }

        @Test
        void browserVerify_thenWebhook_isStillOneSale() throws Exception {
            Map<String, String> ids = initiate(asBuyer, paidProduct, newKey());
            String g = ids.get("gatewayOrderId");
            asBuyer.document(VERIFY).variable("input", Map.of("orderId", ids.get("orderId"), "gatewayOrderId", g,
                            "gatewayPaymentId", "pay_1", "gatewaySignature", checkoutSignature(g, "pay_1")))
                    .execute().path("verifyPayment.status").entity(String.class).isEqualTo("COMPLETED");

            postWebhook(MockWebhookSender.EVENT_CAPTURED, g, "pay_1", PRICE_PAISE, "evt_1").andExpect(status().isOk());

            assertThat(activeEntitlements(buyer, paidProduct)).isEqualTo(1);
            assertThat(totalSales(paidProduct)).isEqualTo(1);
        }

        @Test
        void badSignature_isRejected_andChangesNothing() throws Exception {
            Map<String, String> ids = initiate(asBuyer, paidProduct, newKey());

            postWebhook(MockWebhookSender.EVENT_CAPTURED, ids.get("gatewayOrderId"), "pay_1", PRICE_PAISE, "evt_1",
                    "0000deadbeef").andExpect(status().isBadRequest());

            assertThat(orderStatus(ids.get("orderId"))).isEqualTo("PENDING");
            assertThat(activeEntitlements(buyer, paidProduct)).isZero();
        }

        @Test
        void amountMismatch_doesNotGrantAccess() throws Exception {
            Map<String, String> ids = initiate(asBuyer, paidProduct, newKey());

            postWebhook(MockWebhookSender.EVENT_CAPTURED, ids.get("gatewayOrderId"), "pay_1", 100, "evt_1")
                    .andExpect(status().isOk());   // acknowledged — retrying can't fix it; logged as an ALERT

            assertThat(orderStatus(ids.get("orderId"))).isEqualTo("PENDING");
            assertThat(activeEntitlements(buyer, paidProduct)).isZero();
        }

        @Test
        void decline_thenRetrySucceeds_onTheSameOrder() throws Exception {
            Map<String, String> ids = initiate(asBuyer, paidProduct, newKey());
            String g = ids.get("gatewayOrderId");

            postWebhook(MockWebhookSender.EVENT_FAILED, g, "pay_declined", PRICE_PAISE, "evt_fail");
            assertThat(orderStatus(ids.get("orderId"))).isEqualTo("PENDING");   // order survives a decline (D5)
            asBuyer.document("query($id: ID!) { order(id: $id) { failureReason } }").variable("id", ids.get("orderId"))
                    .execute().path("order.failureReason").entity(String.class).isEqualTo("Card declined");

            postWebhook(MockWebhookSender.EVENT_CAPTURED, g, "pay_ok", PRICE_PAISE, "evt_ok");

            assertThat(orderStatus(ids.get("orderId"))).isEqualTo("COMPLETED");
            List<String> toStatuses = jdbcTemplate.queryForList(
                    "SELECT to_status::text FROM payment_events ORDER BY id", String.class);
            assertThat(toStatuses).containsExactly("PENDING", "FAILED", "COMPLETED");
        }

        @Test
        void lateFailedWebhook_afterSuccess_doesNotUndoThePurchase() throws Exception {
            // Webhooks are not delivered in order.
            Map<String, String> ids = initiate(asBuyer, paidProduct, newKey());
            postWebhook(MockWebhookSender.EVENT_CAPTURED, ids.get("gatewayOrderId"), "pay_ok", PRICE_PAISE, "evt_ok");
            postWebhook(MockWebhookSender.EVENT_FAILED, ids.get("gatewayOrderId"), "pay_old", PRICE_PAISE, "evt_old")
                    .andExpect(status().isOk());

            assertThat(orderStatus(ids.get("orderId"))).isEqualTo("COMPLETED");
            assertThat(activeEntitlements(buyer, paidProduct)).isEqualTo(1);
        }

        @Test
        void concurrentCaptures_produceExactlyOneEntitlement() throws Exception {
            // The race D3 exists for: browser callback + webhook + webhook retries, all at once.
            Map<String, String> ids = initiate(asBuyer, paidProduct, newKey());
            String g = ids.get("gatewayOrderId");

            List<Integer> ignored = runConcurrently(8, () -> {
                String eventId = "evt_" + Thread.currentThread().threadId();
                paymentCompletionService.recordCapture(g, "pay_1", PRICE_PAISE, eventId, "{}");
                return 0;
            });

            assertThat(ignored).hasSize(8);
            assertThat(activeEntitlements(buyer, paidProduct)).isEqualTo(1);
            assertThat(totalSales(paidProduct)).isEqualTo(1);   // D8 — no double count, no lost update
            assertThat(count("SELECT count(*) FROM payment_events WHERE to_status = 'COMPLETED'")).isEqualTo(1);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    class Security {

        @Test
        void anonymous_cannotBuy() {
            // initiateOrder is NON-NULL in the schema (InitiateOrderPayload!), so when it errors the
            // null "bubbles up" to the parent: the whole `data` is null, not just this field.
            anonymous.document(INITIATE).variable("productId", paidProduct.getId()).variable("key", newKey())
                    .execute().errors().expect(e -> e.getExtensions().get("code") != null).verify()
                    .path("$.data").valueIsNull();
            assertThat(count("SELECT count(*) FROM orders")).isZero();
        }

        @Test
        void creator_cannotBuyOwnProduct() {
            asCreator.document(INITIATE).variable("productId", paidProduct.getId()).variable("key", newKey())
                    .execute().errors()
                    .expect(e -> "CANNOT_BUY_OWN_PRODUCT".equals(e.getExtensions().get("code"))).verify();
        }

        @Test
        void owner_cannotBuyAgain() {
            asBuyer.document(INITIATE).variable("productId", freeProduct.getId()).variable("key", newKey()).execute();

            asBuyer.document(INITIATE).variable("productId", freeProduct.getId()).variable("key", newKey())
                    .execute().errors()
                    .expect(e -> "ALREADY_OWNED".equals(e.getExtensions().get("code"))).verify();
        }

        @Test
        void nonLiveProduct_isNotFound() {
            Product p = productRepository.findById(paidProduct.getId()).orElseThrow();
            p.setStatus(ProductStatus.UNPUBLISHED);
            productRepository.save(p);

            asBuyer.document(INITIATE).variable("productId", paidProduct.getId()).variable("key", newKey())
                    .execute().errors().expect(e -> "NOT_FOUND".equals(e.getExtensions().get("code"))).verify();
        }

        @Test
        void anotherBuyersOrder_isNotFound_forReadAndVerify() {
            Map<String, String> ids = initiate(asBuyer, paidProduct, newKey());
            String g = ids.get("gatewayOrderId");

            asOtherBuyer.document("query($id: ID!) { order(id: $id) { id } }").variable("id", ids.get("orderId"))
                    .execute().errors().expect(e -> "NOT_FOUND".equals(e.getExtensions().get("code"))).verify();

            // Even with a VALID signature, you can't complete someone else's order into your account.
            asOtherBuyer.document(VERIFY).variable("input", Map.of("orderId", ids.get("orderId"), "gatewayOrderId", g,
                            "gatewayPaymentId", "pay_1", "gatewaySignature", checkoutSignature(g, "pay_1")))
                    .execute().errors().expect(e -> "NOT_FOUND".equals(e.getExtensions().get("code"))).verify();
            assertThat(orderStatus(ids.get("orderId"))).isEqualTo("PENDING");
        }

        @Test
        void forgedCheckoutSignature_isRejected() {
            Map<String, String> ids = initiate(asBuyer, paidProduct, newKey());

            asBuyer.document(VERIFY).variable("input", Map.of("orderId", ids.get("orderId"),
                            "gatewayOrderId", ids.get("gatewayOrderId"), "gatewayPaymentId", "pay_1",
                            "gatewaySignature", "i-promise-i-paid"))
                    .execute().errors()
                    .expect(e -> "INVALID_PAYMENT_SIGNATURE".equals(e.getExtensions().get("code"))).verify();
            assertThat(activeEntitlements(buyer, paidProduct)).isZero();
        }

        @Test
        void declinedAtGateway_returnsNoSignature() throws Exception {
            Map<String, String> ids = initiate(asBuyer, paidProduct, newKey());
            payAtGateway(ids.get("gatewayOrderId"), "DECLINE").andExpect(status().isPaymentRequired());
            payAtGateway(ids.get("gatewayOrderId"), "TIMEOUT").andExpect(status().isGatewayTimeout());
            // Captured once (by the TIMEOUT) — the gateway refuses to charge the same order again.
            payAtGateway(ids.get("gatewayOrderId"), "SUCCESS").andExpect(status().isBadRequest());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    class DatabaseGuarantees {

        @Test
        void paymentEvents_areAppendOnly() {
            initiate(asBuyer, paidProduct, newKey());

            assertThatThrownBy(() -> jdbcTemplate.update("UPDATE payment_events SET event_source = 'TAMPERED'"))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("append-only");
            assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM payment_events"))
                    .isInstanceOf(DataAccessException.class);
        }

        @Test
        void secondActiveEntitlement_isImpossible_evenBypassingTheApp() {
            asBuyer.document(INITIATE).variable("productId", freeProduct.getId()).variable("key", newKey()).execute();

            // A second, separate order — V1's UNIQUE (order_id, product_id) can't catch this case.
            Long secondOrderId = jdbcTemplate.queryForObject(
                    "INSERT INTO orders (buyer_id, total_amount_paise, status) VALUES (?, 0, 'COMPLETED') RETURNING id",
                    Long.class, buyer.getId());

            // Grant the same product again directly — as a buggy future code path might. D6's
            // partial unique index is what stops it, independent of any Java code.
            assertThatThrownBy(() -> jdbcTemplate.update("""
                    INSERT INTO entitlements (buyer_id, product_id, document_version_id, order_id, status)
                    SELECT buyer_id, product_id, document_version_id, ?, 'ACTIVE' FROM entitlements LIMIT 1
                    """, secondOrderId))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("uq_entitlements_buyer_product_active");
            assertThat(activeEntitlements(buyer, freeProduct)).isEqualTo(1);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    class OwnedByMe {

        private static final String PRODUCTS = "query { products(page: 0, size: 20) { content { title ownedByMe } } }";

        @Test
        void marksOnlyOwnedProducts_forTheCaller() {
            asBuyer.document(INITIATE).variable("productId", freeProduct.getId()).variable("key", newKey()).execute();

            Map<String, Boolean> owned = asBuyer.document(PRODUCTS).execute()
                    .path("products.content").entityList(ProductOwnership.class).get().stream()
                    .collect(Collectors.toMap(ProductOwnership::title, ProductOwnership::ownedByMe));

            assertThat(owned).containsEntry("Free Guide", true).containsEntry("Paid Guide", false);
        }

        @Test
        void isFalseForAnonymousAndForOtherUsers() {
            asBuyer.document(INITIATE).variable("productId", freeProduct.getId()).variable("key", newKey()).execute();

            anonymous.document(PRODUCTS).execute()
                    .path("products.content[*].ownedByMe").entityList(Boolean.class).containsExactly(false, false);
            asOtherBuyer.document(PRODUCTS).execute()
                    .path("products.content[*].ownedByMe").entityList(Boolean.class).containsExactly(false, false);
        }
    }

    record ProductOwnership(String title, boolean ownedByMe) {}

    // ── helpers ─────────────────────────────────────────────────────────────

    private static <T> List<T> runConcurrently(int threads, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) futures.add(pool.submit(task));
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) results.add(f.get());
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
