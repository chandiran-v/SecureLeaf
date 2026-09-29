package com.secureleaf.commerce;

import com.jayway.jsonpath.JsonPath;
import com.secureleaf.auth.entity.Role;
import com.secureleaf.commerce.gateway.MockRazorpayGateway;
import com.secureleaf.commerce.gateway.MockWebhookSender;
import com.secureleaf.commerce.gateway.RazorpaySignatures;
import com.secureleaf.commerce.service.PaymentReconciliationJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 09B acceptance criteria 4, 5, 6 and 8 — the lifecycle around the real gateway, run against
 * the stateful mock gateway (which implements the same {@code PaymentGateway} port, so every line of
 * business logic exercised here is the production code path).
 */
class RazorpayFlowIT extends AbstractCommerceIT {

    private static final String REFUND = """
            mutation($orderId: ID!, $reason: String!) {
              refundOrder(orderId: $orderId, reason: $reason) { id status }
            }
            """;

    private static final String STATS = """
            query { platformStats(days: 30) {
              platformFeePaiseAllTime gatewayFeesPaise platformNetPaise
              platformFeePaiseWindow gatewayFeesPaiseWindow platformNetPaiseWindow } }
            """;

    private static final String EARNINGS = "query { creatorEarnings { salesCount netEarningsPaise } }";

    @Autowired private MockRazorpayGateway mockGateway;
    @Autowired private PaymentReconciliationJob reconciliationJob;

    private HttpGraphQlTester asAdmin;

    @BeforeEach
    void setUpAdmin() {
        asAdmin = tester(makeUser("admin@secureleaf.test", "Ada Admin", Role.BUYER, Role.ADMIN));
    }

    /** initiate → pay at the mock gateway → verifyPayment. Returns {orderId, gatewayOrderId, paymentId}. */
    private Map<String, String> purchase(HttpGraphQlTester as) throws Exception {
        Map<String, String> ids = initiate(as, paidProduct, newKey());
        String body = payAtGateway(ids.get("gatewayOrderId"), "SUCCESS").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String paymentId = JsonPath.read(body, "$.razorpay_payment_id");
        as.document(VERIFY).variable("input", Map.of(
                        "orderId", ids.get("orderId"),
                        "gatewayOrderId", ids.get("gatewayOrderId"),
                        "gatewayPaymentId", paymentId,
                        "gatewaySignature", JsonPath.read(body, "$.razorpay_signature")))
                .execute().path("verifyPayment.status").entity(String.class).isEqualTo("COMPLETED");
        return Map.of("orderId", ids.get("orderId"), "gatewayOrderId", ids.get("gatewayOrderId"), "paymentId", paymentId);
    }

    private void postRefundWebhook(Map<String, String> purchase, String refundId, String eventId) throws Exception {
        String body = mockWebhookSender.buildRefundEventBody(purchase.get("gatewayOrderId"), purchase.get("paymentId"),
                refundId, PRICE_PAISE);
        mockMvc.perform(post("/api/webhooks/razorpay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Razorpay-Signature", RazorpaySignatures.webhookSignature(body, gatewayProperties.webhookSecret()))
                        .header("X-Razorpay-Event-Id", eventId)
                        .content(body))
                .andExpect(status().isOk());
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    class Checkout {

        @Test
        void initiateOrder_exposesProviderAndKey_andPlatformInfoIsPublic() {
            asBuyer.document(INITIATE.replace("gatewayOrderId gatewayKeyId currency", "gatewayOrderId gatewayKeyId currency provider"))
                    .variable("productId", paidProduct.getId()).variable("key", newKey())
                    .execute()
                    .path("initiateOrder.provider").entity(String.class).isEqualTo("MOCK")
                    .path("initiateOrder.gatewayKeyId").entity(String.class).isEqualTo(gatewayProperties.keyId());

            anonymous.document("query { platformInfo { paymentMode supportEmail } }").execute()
                    .path("platformInfo.paymentMode").entity(String.class).isEqualTo("MOCK")
                    .path("platformInfo.supportEmail").entity(String.class).satisfies(e -> assertThat(e).contains("@"));
        }

        @Test
        void order_query_exposesProviderAndKeyOnlyWhilePending() throws Exception {
            Map<String, String> ids = initiate(asBuyer, paidProduct, newKey());
            String q = "query($id: ID!) { order(id: $id) { status paymentProvider gatewayKeyId } }";

            asBuyer.document(q).variable("id", ids.get("orderId")).execute()
                    .path("order.paymentProvider").entity(String.class).isEqualTo("MOCK")
                    .path("order.gatewayKeyId").entity(String.class).isEqualTo(gatewayProperties.keyId());

            purchase(asOtherBuyer);   // unrelated; keeps the mock busy
            String orderId = purchase(asBuyer).get("orderId");
            asBuyer.document(q).variable("id", orderId).execute()
                    .path("order.status").entity(String.class).isEqualTo("COMPLETED")
                    .path("order.gatewayKeyId").valueIsNull();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    class Authorization {

        @Test
        void paymentAuthorized_callsCaptureOnce_thenCapturedWebhookCompletesTheOrder_andDuplicatesAreHarmless() throws Exception {
            Map<String, String> ids = initiate(asBuyer, paidProduct, newKey());
            String gw = ids.get("gatewayOrderId");
            mockGateway.registerPayment(gw, "pay_auth1", "authorized");
            int capturesBefore = mockGateway.captureCalls().size();

            postWebhook(MockWebhookSender.EVENT_AUTHORIZED, gw, "pay_auth1", PRICE_PAISE, "evt_auth_1").andExpect(status().isOk());

            assertThat(mockGateway.captureCalls().subList(capturesBefore, mockGateway.captureCalls().size()))
                    .containsExactly("pay_auth1");
            assertThat(orderStatus(ids.get("orderId"))).isEqualTo("PENDING");   // the captured webhook completes it, not this one

            postWebhook(MockWebhookSender.EVENT_CAPTURED, gw, "pay_auth1", PRICE_PAISE, "evt_cap_1").andExpect(status().isOk());
            postWebhook(MockWebhookSender.EVENT_CAPTURED, gw, "pay_auth1", PRICE_PAISE, "evt_cap_1").andExpect(status().isOk());   // duplicate delivery
            postWebhook(MockWebhookSender.EVENT_CAPTURED, gw, "pay_auth1", PRICE_PAISE, "evt_cap_2").andExpect(status().isOk());   // different event, same payment

            assertThat(orderStatus(ids.get("orderId"))).isEqualTo("COMPLETED");
            assertThat(activeEntitlements(buyer, paidProduct)).isEqualTo(1);
            assertThat(totalSales(paidProduct)).isEqualTo(1);
        }

        @Test
        void paymentAuthorized_withWrongAmount_isNotCaptured() throws Exception {
            Map<String, String> ids = initiate(asBuyer, paidProduct, newKey());
            String gw = ids.get("gatewayOrderId");
            mockGateway.registerPayment(gw, "pay_auth2", "authorized");
            int capturesBefore = mockGateway.captureCalls().size();

            postWebhook(MockWebhookSender.EVENT_AUTHORIZED, gw, "pay_auth2", PRICE_PAISE - 1, "evt_auth_2").andExpect(status().isOk());

            assertThat(mockGateway.captureCalls()).hasSize(capturesBefore);
        }

        @Test
        void paymentAuthorized_whenCaptureCallFails_answers503SoRazorpayRetries() throws Exception {
            Map<String, String> ids = initiate(asBuyer, paidProduct, newKey());
            // "pay_unknown" was never registered at the (mock) gateway, so capture() fails like an outage.
            postWebhook(MockWebhookSender.EVENT_AUTHORIZED, ids.get("gatewayOrderId"), "pay_unknown", PRICE_PAISE, "evt_auth_3")
                    .andExpect(status().isServiceUnavailable());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    class Fees {

        @Test
        void feesAreStoredOnCapture_andPlatformStatsShowFeesAndNet() throws Exception {
            purchase(asBuyer);
            purchase(asOtherBuyer);

            long fee = MockRazorpayGateway.feeFor(PRICE_PAISE);          // 998
            long tax = MockRazorpayGateway.taxOnFee(fee);                // 180
            assertThat(jdbcTemplate.queryForList("SELECT gateway_fee_paise + gateway_tax_paise FROM payments", Long.class))
                    .containsExactly(fee + tax, fee + tax);

            // Creator earnings are untouched by gateway fees: price − 10% snapshot.
            asCreator.document(EARNINGS).execute()
                    .path("creatorEarnings.netEarningsPaise").entity(Long.class).isEqualTo(2 * 44_910L);

            long platformFee = 2 * 4_990L;
            long gatewayFees = 2 * (fee + tax);
            asAdmin.document(STATS).execute()
                    .path("platformStats.platformFeePaiseAllTime").entity(Long.class).isEqualTo(platformFee)
                    .path("platformStats.gatewayFeesPaise").entity(Long.class).isEqualTo(gatewayFees)
                    .path("platformStats.platformNetPaise").entity(Long.class).isEqualTo(platformFee - gatewayFees)
                    .path("platformStats.gatewayFeesPaiseWindow").entity(Long.class).isEqualTo(gatewayFees)
                    .path("platformStats.platformNetPaiseWindow").entity(Long.class).isEqualTo(platformFee - gatewayFees);
        }

        @Test
        void aFailedFeeLookup_doesNotBlockTheBuyer_andLeavesFeesNull() throws Exception {
            // Webhook for a payment the (mock) gateway has never heard of → fetchPayment fails.
            Map<String, String> ids = initiate(asBuyer, paidProduct, newKey());
            postWebhook(MockWebhookSender.EVENT_CAPTURED, ids.get("gatewayOrderId"), "pay_ghost", PRICE_PAISE, "evt_ghost")
                    .andExpect(status().isOk());

            assertThat(orderStatus(ids.get("orderId"))).isEqualTo("COMPLETED");
            assertThat(activeEntitlements(buyer, paidProduct)).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM payments WHERE gateway_fee_paise IS NULL")).isEqualTo(1);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    class Refunds {

        @Test
        void adminRefund_changesStatuses_revokesAccess_auditsAndNotifies_andCreatorEarningsDrop() throws Exception {
            Map<String, String> p = purchase(asBuyer);
            asCreator.document(EARNINGS).execute()
                    .path("creatorEarnings.salesCount").entity(Long.class).isEqualTo(1L);

            asAdmin.document(REFUND).variable("orderId", p.get("orderId")).variable("reason", "Duplicate purchase")
                    .execute().path("refundOrder.status").entity(String.class).isEqualTo("REFUNDED");

            assertThat(orderStatus(p.get("orderId"))).isEqualTo("REFUNDED");
            assertThat(jdbcTemplate.queryForObject("SELECT status::text FROM payments", String.class)).isEqualTo("REFUNDED");
            assertThat(jdbcTemplate.queryForObject("SELECT refund_id FROM payments", String.class)).startsWith("rfnd_");
            assertThat(mockGateway.refundCalls()).contains(p.get("paymentId"));

            // Entitlement revoked with a reason; the reader is closed to the buyer.
            Map<String, Object> ent = jdbcTemplate.queryForMap("SELECT status::text AS status, revocation_reason, revoked_at FROM entitlements");
            assertThat(ent.get("status")).isEqualTo("REVOKED");
            assertThat((String) ent.get("revocation_reason")).contains("Duplicate purchase");
            assertThat(ent.get("revoked_at")).isNotNull();
            asBuyer.document("mutation($p: ID!) { startViewerSession(productId: $p, deviceFingerprint: \"d\") { sessionId } }")
                    .variable("p", paidProduct.getId())
                    .execute().errors().expect(e -> "NOT_ENTITLED".equals(e.getExtensions().get("code"))).verify();

            // Audit trail: payment_events + admin_actions + buyer notification.
            assertThat(jdbcTemplate.queryForList("SELECT to_status::text || ' (' || event_source || ')' FROM payment_events ORDER BY id", String.class))
                    .last().isEqualTo("REFUNDED (ADMIN_REFUND)");
            assertThat(count("SELECT count(*) FROM admin_actions WHERE action = 'REFUND_ORDER' AND target_type = 'ORDER' AND target_id = ? AND reason = 'Duplicate purchase'",
                    Long.valueOf(p.get("orderId")))).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM notifications WHERE type = 'REFUND_PROCESSED' AND recipient_id = ?", buyer.getId()))
                    .isEqualTo(1);

            // Creator earnings exclude the refunded order; the sales counter follows.
            asCreator.document(EARNINGS).execute()
                    .path("creatorEarnings.salesCount").entity(Long.class).isEqualTo(0L)
                    .path("creatorEarnings.netEarningsPaise").entity(Long.class).isEqualTo(0L);
            assertThat(totalSales(paidProduct)).isEqualTo(0);
        }

        @Test
        void replayedRefundProcessedWebhook_changesNothing() throws Exception {
            Map<String, String> p = purchase(asBuyer);
            asAdmin.document(REFUND).variable("orderId", p.get("orderId")).variable("reason", "Requested by buyer").executeAndVerify();
            int events = count("SELECT count(*) FROM payment_events");
            int notifications = count("SELECT count(*) FROM notifications");
            int adminActions = count("SELECT count(*) FROM admin_actions");

            postRefundWebhook(p, "rfnd_replay", "evt_refund_1");
            postRefundWebhook(p, "rfnd_replay", "evt_refund_1");   // same delivery again

            assertThat(count("SELECT count(*) FROM payment_events")).isEqualTo(events);
            assertThat(count("SELECT count(*) FROM notifications")).isEqualTo(notifications);
            assertThat(count("SELECT count(*) FROM admin_actions")).isEqualTo(adminActions);
            assertThat(orderStatus(p.get("orderId"))).isEqualTo("REFUNDED");
        }

        @Test
        void refundProcessedWebhook_forARefundMadeInTheGatewayDashboard_appliesItOnce() throws Exception {
            Map<String, String> p = purchase(asBuyer);

            postRefundWebhook(p, "rfnd_dash", "evt_refund_dash");
            postRefundWebhook(p, "rfnd_dash", "evt_refund_dash");

            assertThat(orderStatus(p.get("orderId"))).isEqualTo("REFUNDED");
            assertThat(activeEntitlements(buyer, paidProduct)).isZero();
            assertThat(count("SELECT count(*) FROM notifications WHERE type = 'REFUND_PROCESSED'")).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM admin_actions")).isZero();   // no admin acted
            assertThat(count("SELECT count(*) FROM payment_events WHERE to_status = 'REFUNDED'")).isEqualTo(1);
        }

        @Test
        void refundWebhook_withBadSignature_isRejected() throws Exception {
            Map<String, String> p = purchase(asBuyer);
            String body = mockWebhookSender.buildRefundEventBody(p.get("gatewayOrderId"), p.get("paymentId"), "rfnd_x", PRICE_PAISE);

            mockMvc.perform(post("/api/webhooks/razorpay").contentType(MediaType.APPLICATION_JSON)
                            .header("X-Razorpay-Signature", "deadbeef").header("X-Razorpay-Event-Id", "evt_bad").content(body))
                    .andExpect(status().isBadRequest());
            assertThat(orderStatus(p.get("orderId"))).isEqualTo("COMPLETED");
        }

        @Test
        void nonAdmin_getsAccessDenied_andNothingChanges() throws Exception {
            Map<String, String> p = purchase(asBuyer);

            for (HttpGraphQlTester who : List.of(asBuyer, asCreator, anonymous)) {
                who.document(REFUND).variable("orderId", p.get("orderId")).variable("reason", "x")
                        .execute().errors().expect(e -> "ACCESS_DENIED".equals(e.getExtensions().get("code"))
                                || "UNAUTHORIZED".equals(e.getExtensions().get("code"))).verify();
            }
            assertThat(orderStatus(p.get("orderId"))).isEqualTo("COMPLETED");
            assertThat(activeEntitlements(buyer, paidProduct)).isEqualTo(1);
        }

        @Test
        void refund_needsAReason_aCompletedOrder_andCannotBeRepeated() throws Exception {
            Map<String, String> pending = initiate(asBuyer, paidProduct, newKey());
            asAdmin.document(REFUND).variable("orderId", pending.get("orderId")).variable("reason", "why not")
                    .execute().errors().expect(e -> "INVALID_STATE_TRANSITION".equals(e.getExtensions().get("code"))).verify();

            Map<String, String> p = purchase(asOtherBuyer);
            asAdmin.document(REFUND).variable("orderId", p.get("orderId")).variable("reason", "  ")
                    .execute().errors().expect(e -> "INVALID_INPUT".equals(e.getExtensions().get("code"))).verify();

            asAdmin.document(REFUND).variable("orderId", p.get("orderId")).variable("reason", "ok").executeAndVerify();
            asAdmin.document(REFUND).variable("orderId", p.get("orderId")).variable("reason", "again")
                    .execute().errors().expect(e -> "INVALID_STATE_TRANSITION".equals(e.getExtensions().get("code"))).verify();
            assertThat(mockGateway.refundCalls().stream().filter(id -> id.equals(p.get("paymentId"))).count()).isEqualTo(1);
        }

        @Test
        void afterARefund_thePlatformStillPaidTheGatewayFee_butEarnsNoCommission() throws Exception {
            Map<String, String> p = purchase(asBuyer);
            asAdmin.document(REFUND).variable("orderId", p.get("orderId")).variable("reason", "refund").executeAndVerify();

            long gatewayFees = MockRazorpayGateway.feeFor(PRICE_PAISE) + MockRazorpayGateway.taxOnFee(MockRazorpayGateway.feeFor(PRICE_PAISE));
            asAdmin.document(STATS).execute()
                    .path("platformStats.platformFeePaiseAllTime").entity(Long.class).isEqualTo(0L)
                    .path("platformStats.gatewayFeesPaise").entity(Long.class).isEqualTo(gatewayFees)
                    .path("platformStats.platformNetPaise").entity(Long.class).isEqualTo(-gatewayFees);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    @Nested
    class Reconciliation {

        private void ageOrder(String orderId, String interval) {
            jdbcTemplate.update("UPDATE orders SET created_at = now() - CAST(? AS interval) WHERE id = ?", interval, Long.valueOf(orderId));
        }

        @Test
        void pendingOrderWhoseGatewayShowsACapture_isCompleted_onceOnly() throws Exception {
            Map<String, String> ids = initiate(asBuyer, paidProduct, newKey());
            // The buyer paid (mock gateway captured) but neither the browser callback nor a webhook arrived.
            payAtGateway(ids.get("gatewayOrderId"), "SUCCESS").andExpect(status().isOk());
            ageOrder(ids.get("orderId"), "20 minutes");

            assertThat(reconciliationJob.reconcile()).isEqualTo(1);
            assertThat(reconciliationJob.reconcile()).isZero();   // second sweep: nothing left to do

            assertThat(orderStatus(ids.get("orderId"))).isEqualTo("COMPLETED");
            assertThat(activeEntitlements(buyer, paidProduct)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForList("SELECT event_source FROM payment_events ORDER BY id", String.class))
                    .containsExactly("CHECKOUT", "RECONCILIATION");
            assertThat(count("SELECT count(*) FROM payments WHERE gateway_fee_paise IS NOT NULL")).isEqualTo(1);
        }

        @Test
        void pendingOrderWithAnAuthorizedPayment_isCapturedThenCompleted() throws Exception {
            Map<String, String> ids = initiate(asBuyer, paidProduct, newKey());
            mockGateway.registerPayment(ids.get("gatewayOrderId"), "pay_recon_auth", "authorized");
            ageOrder(ids.get("orderId"), "30 minutes");

            reconciliationJob.reconcile();

            assertThat(mockGateway.captureCalls()).contains("pay_recon_auth");
            assertThat(orderStatus(ids.get("orderId"))).isEqualTo("COMPLETED");
        }

        @Test
        void orderOlderThan24Hours_withNothingCaptured_becomesFailedExpired() throws Exception {
            Map<String, String> ids = initiate(asBuyer, paidProduct, newKey());
            ageOrder(ids.get("orderId"), "25 hours");

            assertThat(reconciliationJob.reconcile()).isEqualTo(1);

            assertThat(orderStatus(ids.get("orderId"))).isEqualTo("FAILED");
            assertThat(jdbcTemplate.queryForObject("SELECT failure_reason FROM payments", String.class)).isEqualTo("expired");
            assertThat(jdbcTemplate.queryForObject("SELECT status::text FROM payments", String.class)).isEqualTo("FAILED");
            assertThat(activeEntitlements(buyer, paidProduct)).isZero();
        }

        @Test
        void youngPendingOrders_andAlreadyCompletedOrders_areNotTouched() throws Exception {
            Map<String, String> young = initiate(asBuyer, paidProduct, newKey());
            ageOrder(young.get("orderId"), "5 minutes");                       // too young to look at

            Map<String, String> done = purchase(asOtherBuyer);
            ageOrder(done.get("orderId"), "30 hours");                          // old, but already COMPLETED
            int events = count("SELECT count(*) FROM payment_events");

            assertThat(reconciliationJob.reconcile()).isZero();

            assertThat(orderStatus(young.get("orderId"))).isEqualTo("PENDING");
            assertThat(orderStatus(done.get("orderId"))).isEqualTo("COMPLETED");
            assertThat(count("SELECT count(*) FROM payment_events")).isEqualTo(events);
        }

        @Test
        void oldOrderBetween15MinutesAnd24Hours_withNothingCaptured_staysPending() throws Exception {
            Map<String, String> ids = initiate(asBuyer, paidProduct, newKey());
            ageOrder(ids.get("orderId"), "3 hours");

            assertThat(reconciliationJob.reconcile()).isZero();
            assertThat(orderStatus(ids.get("orderId"))).isEqualTo("PENDING");
        }
    }
}
