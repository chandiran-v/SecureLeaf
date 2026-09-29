package com.secureleaf.creator;

import com.secureleaf.auth.entity.Role;
import com.secureleaf.auth.entity.User;
import com.secureleaf.commerce.AbstractCommerceIT;
import com.secureleaf.common.exception.BusinessException;
import com.secureleaf.creator.service.PayoutService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 09C acceptance criteria 1–5 (docs/phases/phase-09c-payouts-receipts-legal.md).
 *
 * Fixture (creator earns 90% of every price; ₹1000 → fee ₹100, earnings ₹900):
 *   A  ₹1000  10 days ago  COMPLETED           → cleared   90,000 paise
 *   B  ₹1000  10 days ago  REFUNDED            → excluded everywhere
 *   C  ₹1000   2 days ago  COMPLETED           → pending   90,000 paise (inside the 7-day hold)
 *   D  ₹500    8 days ago  COMPLETED           → cleared   45,000 paise
 *   plus an existing PAID payout of ₹200 (20,000 paise)
 * ⇒ lifetime 225,000 · cleared 135,000 · pending 90,000 · paid out 20,000 · available 115,000
 */
class PayoutIT extends AbstractCommerceIT {

    private static final long HUNDRED = 10_000;

    @Autowired private PayoutService payoutService;

    private User admin;
    private HttpGraphQlTester asAdmin;

    private static final String BALANCE = "query { creatorBalance { availablePaise pendingPaise lifetimeEarningsPaise paidOutPaise } }";
    private static final String REQUEST = "mutation($a: Long!) { requestPayout(amountPaise: $a) { id status amountPaise payoutDestination netPayoutPaise } }";
    private static final String APPROVE = "mutation($id: ID!) { approvePayout(id: $id) { id status } }";
    private static final String PAY = "mutation($id: ID!, $r: String!) { markPayoutPaid(id: $id, reference: $r) { id status payoutReference } }";
    private static final String REJECT = "mutation($id: ID!, $r: String!) { rejectPayout(id: $id, reason: $r) { id status notes } }";

    @BeforeEach
    void setUp() {
        admin = makeUser("admin@example.com", "Ada Admin", Role.BUYER, Role.ADMIN);
        asAdmin = tester(admin);
        jdbcTemplate.update("INSERT INTO creator_profiles (user_id, payout_upi) VALUES (?, ?)", creator.getId(), "casey@upi");
    }

    // ── fixture helpers ─────────────────────────────────────────────────────

    /** Inserts a paid order + item (+ payment) directly; returns the order id. */
    private long sale(User forBuyer, long price, int completedDaysAgo, boolean refunded, int refundedDaysAgo) {
        long fee = price / 10;
        Long orderId = jdbcTemplate.queryForObject("""
                INSERT INTO orders (buyer_id, total_amount_paise, status, completed_at)
                VALUES (?, ?, ?::order_status, NOW() - make_interval(days => ?)) RETURNING id
                """, Long.class, forBuyer.getId(), price, refunded ? "REFUNDED" : "COMPLETED", completedDaysAgo);
        jdbcTemplate.update("INSERT INTO order_items (order_id, product_id, price_paise, platform_fee_paise, creator_earnings_paise) VALUES (?,?,?,?,?)",
                orderId, paidProduct.getId(), price, fee, price - fee);
        jdbcTemplate.update("""
                INSERT INTO payments (order_id, idempotency_key, provider_payment_id, provider_name, amount_paise, status, refunded_at)
                VALUES (?, ?, ?, 'RAZORPAY', ?, ?::payment_status, CASE WHEN ? THEN NOW() - make_interval(days => ?) END)
                """, orderId, "k-" + orderId, "pay_TESTPAYMENT" + orderId, price, refunded ? "REFUNDED" : "COMPLETED",
                refunded, refundedDaysAgo);
        return orderId;
    }

    private void balanceFixture() {
        sale(buyer, 100_000, 10, false, 0);
        sale(otherBuyer, 100_000, 10, true, 5);
        sale(buyer, 100_000, 2, false, 0);
        sale(otherBuyer, 50_000, 8, false, 0);
        jdbcTemplate.update("""
                INSERT INTO creator_payouts (creator_id, amount_paise, status, gross_revenue_paise, platform_fee_paise,
                                             net_payout_paise, processed_at)
                VALUES (?, 20000, 'PAID', 0, 0, 20000, NOW())
                """, creator.getId());
    }

    private long requestOk(long amount) {
        return Long.parseLong(asCreator.document(REQUEST).variable("a", amount).execute()
                .path("requestPayout.id").entity(String.class).get());
    }

    private void expectCode(HttpGraphQlTester.Request<?> r, String code) {
        r.execute().errors().expect(e -> code.equals(e.getExtensions().get("code"))).verify();
    }

    private String payoutStatus(long id) {
        return jdbcTemplate.queryForObject("SELECT status::text FROM creator_payouts WHERE id = ?", String.class, id);
    }

    // ── AC1 balance ─────────────────────────────────────────────────────────

    @Test
    void balanceMathIsExactOnTheFixture() {
        balanceFixture();
        asCreator.document(BALANCE).execute()
                .path("creatorBalance.availablePaise").entity(Long.class).isEqualTo(115_000L)
                .path("creatorBalance.pendingPaise").entity(Long.class).isEqualTo(90_000L)
                .path("creatorBalance.lifetimeEarningsPaise").entity(Long.class).isEqualTo(225_000L)
                .path("creatorBalance.paidOutPaise").entity(Long.class).isEqualTo(20_000L);
    }

    @Test
    void balanceIsCreatorOnly() {
        expectCode(asBuyer.document(BALANCE), "ACCESS_DENIED");
    }

    // ── AC2 requestPayout ───────────────────────────────────────────────────

    @Nested
    class RequestPayout {

        @BeforeEach
        void fixture() {
            balanceFixture();
        }

        @Test
        void succeedsAndSnapshotsTheDestination() {
            asCreator.document(REQUEST).variable("a", 50_000L).execute()
                    .path("requestPayout.status").entity(String.class).isEqualTo("REQUESTED")
                    .path("requestPayout.payoutDestination").entity(String.class).isEqualTo("casey@upi")
                    .path("requestPayout.netPayoutPaise").entity(Long.class).isEqualTo(50_000L);
            // the request now holds the money: 115,000 − 50,000
            asCreator.document(BALANCE).execute().path("creatorBalance.availablePaise").entity(Long.class).isEqualTo(65_000L);
            // ...and every admin was told
            assertThat(count("SELECT count(*) FROM notifications WHERE recipient_id = ? AND type = 'PAYOUT_REQUESTED'", admin.getId())).isEqualTo(1);
        }

        @Test
        void rejectedBelowTheMinimum() {
            expectCode(asCreator.document(REQUEST).variable("a", HUNDRED - 1), "PAYOUT_BELOW_MINIMUM");
        }

        @Test
        void rejectedAboveTheAvailableBalance() {
            expectCode(asCreator.document(REQUEST).variable("a", 115_001L), "INSUFFICIENT_BALANCE");
        }

        @Test
        void rejectedWhileAnotherRequestIsOpen() {
            requestOk(20_000);
            expectCode(asCreator.document(REQUEST).variable("a", HUNDRED), "PAYOUT_ALREADY_OPEN");
        }

        @Test
        void rejectedWithoutPayoutDetails() {
            jdbcTemplate.update("UPDATE creator_profiles SET payout_upi = NULL, payout_email = NULL");
            expectCode(asCreator.document(REQUEST).variable("a", HUNDRED), "PAYOUT_DETAILS_MISSING");
        }

        @Test
        void twoConcurrentRequestsExactlyOneSucceeds() throws Exception {
            int threads = 2;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        payoutService.requestPayout(creator.getId(), 100_000);  // each alone fits; together they don't
                        return true;
                    } catch (BusinessException e) {
                        return false;
                    }
                }));
            }
            ready.await();
            go.countDown();
            int wins = 0;
            for (Future<Boolean> f : results) if (f.get()) wins++;
            pool.shutdownNow();

            assertThat(wins).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM creator_payouts WHERE status = 'REQUESTED'")).isEqualTo(1);
        }
    }

    @Test
    void payoutDetailsCanBeEditedAndValidated() {
        asCreator.document("mutation { updatePayoutDetails(payoutUpi: \"new@okbank\", payoutEmail: null) { payoutUpi payoutEmail } }")
                .execute().path("updatePayoutDetails.payoutUpi").entity(String.class).isEqualTo("new@okbank");
        expectCode(asCreator.document("mutation { updatePayoutDetails(payoutUpi: \"not a upi\") { payoutUpi } }"), "INVALID_INPUT");
    }

    // ── AC3 state machine, audit, notifications ─────────────────────────────

    @Nested
    class AdminWorkflow {

        private long payoutId;

        @BeforeEach
        void fixture() {
            balanceFixture();
            payoutId = requestOk(50_000);
        }

        private int audits(String action) {
            return count("SELECT count(*) FROM admin_actions WHERE action = ? AND target_type = 'PAYOUT' AND target_id = ?", action, payoutId);
        }

        private int creatorNotifications() {
            return count("SELECT count(*) FROM notifications WHERE recipient_id = ? AND type = 'PAYOUT_STATUS_UPDATE'", creator.getId());
        }

        @Test
        void happyPathRequestedApprovedPaid() {
            asAdmin.document(APPROVE).variable("id", payoutId).execute()
                    .path("approvePayout.status").entity(String.class).isEqualTo("APPROVED");
            assertThat(audits("APPROVE_PAYOUT")).isEqualTo(1);
            assertThat(creatorNotifications()).isEqualTo(1);

            asAdmin.document(PAY).variable("id", payoutId).variable("r", "UPI-123456").execute()
                    .path("markPayoutPaid.status").entity(String.class).isEqualTo("PAID")
                    .path("markPayoutPaid.payoutReference").entity(String.class).isEqualTo("UPI-123456");
            assertThat(audits("MARK_PAYOUT_PAID")).isEqualTo(1);
            assertThat(creatorNotifications()).isEqualTo(2);
            assertThat(count("SELECT count(*) FROM creator_payouts WHERE id = ? AND processed_at IS NOT NULL AND approved_by = ?",
                    payoutId, admin.getId())).isEqualTo(1);
        }

        @Test
        void rejectFromRequestedAndFromApproved() {
            asAdmin.document(REJECT).variable("id", payoutId).variable("r", "Details unclear").execute()
                    .path("rejectPayout.status").entity(String.class).isEqualTo("REJECTED");
            assertThat(audits("REJECT_PAYOUT")).isEqualTo(1);
            assertThat(creatorNotifications()).isEqualTo(1);

            long second = requestOk(HUNDRED);   // the reject freed the "one open request" slot
            asAdmin.document(APPROVE).variable("id", second).execute();
            asAdmin.document(REJECT).variable("id", second).variable("r", "Changed my mind").execute()
                    .path("rejectPayout.status").entity(String.class).isEqualTo("REJECTED");
        }

        @Test
        void everyIllegalTransitionIsRefused() {
            // REQUESTED → PAID is illegal (must be approved first)
            expectCode(asAdmin.document(PAY).variable("id", payoutId).variable("r", "x"), "INVALID_STATE_TRANSITION");
            asAdmin.document(APPROVE).variable("id", payoutId).execute();
            // APPROVED → APPROVED
            expectCode(asAdmin.document(APPROVE).variable("id", payoutId), "INVALID_STATE_TRANSITION");
            asAdmin.document(PAY).variable("id", payoutId).variable("r", "ref").execute();
            // PAID is terminal
            expectCode(asAdmin.document(APPROVE).variable("id", payoutId), "INVALID_STATE_TRANSITION");
            expectCode(asAdmin.document(PAY).variable("id", payoutId).variable("r", "again"), "INVALID_STATE_TRANSITION");
            expectCode(asAdmin.document(REJECT).variable("id", payoutId).variable("r", "late"), "INVALID_STATE_TRANSITION");
            assertThat(payoutStatus(payoutId)).isEqualTo("PAID");
            // exactly the two legal actions were audited — the refused ones wrote nothing
            assertThat(count("SELECT count(*) FROM admin_actions WHERE target_type = 'PAYOUT'")).isEqualTo(2);
        }

        @Test
        void rejectedIsTerminal() {
            asAdmin.document(REJECT).variable("id", payoutId).variable("r", "no").execute();
            expectCode(asAdmin.document(APPROVE).variable("id", payoutId), "INVALID_STATE_TRANSITION");
            expectCode(asAdmin.document(PAY).variable("id", payoutId).variable("r", "x"), "INVALID_STATE_TRANSITION");
            expectCode(asAdmin.document(REJECT).variable("id", payoutId).variable("r", "again"), "INVALID_STATE_TRANSITION");
        }

        @Test
        void reasonAndReferenceAreRequired() {
            asAdmin.document(APPROVE).variable("id", payoutId).execute();
            expectCode(asAdmin.document(PAY).variable("id", payoutId).variable("r", "  "), "INVALID_INPUT");
            expectCode(asAdmin.document(REJECT).variable("id", payoutId).variable("r", ""), "INVALID_INPUT");
            assertThat(payoutStatus(payoutId)).isEqualTo("APPROVED");
        }

        @Test
        void nonAdminsAreDenied() {
            expectCode(asCreator.document(APPROVE).variable("id", payoutId), "ACCESS_DENIED");
            expectCode(asBuyer.document(REJECT).variable("id", payoutId).variable("r", "x"), "ACCESS_DENIED");
            expectCode(asCreator.document("query { adminPayouts { totalElements } }"), "ACCESS_DENIED");
            assertThat(payoutStatus(payoutId)).isEqualTo("REQUESTED");
        }

        @Test
        void adminListsPayoutsFilteredByStatus() {
            asAdmin.document("query { adminPayouts(status: REQUESTED) { totalElements content { id creatorEmail payoutDestination } } }")
                    .execute().path("adminPayouts.totalElements").entity(Integer.class).isEqualTo(1)
                    .path("adminPayouts.content[0].payoutDestination").entity(String.class).isEqualTo("casey@upi");
            asAdmin.document("query { adminPayouts(status: APPROVED) { totalElements } }")
                    .execute().path("adminPayouts.totalElements").entity(Integer.class).isEqualTo(0);
            // the seeded PAID row + the REQUESTED one
            asAdmin.document("query { adminPayouts { totalElements } }")
                    .execute().path("adminPayouts.totalElements").entity(Integer.class).isEqualTo(2);
        }

        // ── AC4 ─────────────────────────────────────────────────────────────

        @Test
        void aRejectedAmountBecomesAvailableAgainAndAPaidOneNeverDoes() {
            asCreator.document(BALANCE).execute().path("creatorBalance.availablePaise").entity(Long.class).isEqualTo(65_000L);

            asAdmin.document(REJECT).variable("id", payoutId).variable("r", "retry").execute();
            asCreator.document(BALANCE).execute().path("creatorBalance.availablePaise").entity(Long.class).isEqualTo(115_000L);

            long second = requestOk(50_000);
            asAdmin.document(APPROVE).variable("id", second).execute();
            asAdmin.document(PAY).variable("id", second).variable("r", "UTR1").execute();
            asCreator.document(BALANCE).execute()
                    .path("creatorBalance.availablePaise").entity(Long.class).isEqualTo(65_000L)
                    .path("creatorBalance.paidOutPaise").entity(Long.class).isEqualTo(70_000L);
        }
    }

    // ── AC5 statement + CSV ─────────────────────────────────────────────────

    @Nested
    class Statement {

        private final ZoneId ist = ZoneId.of("Asia/Kolkata");
        private final String month = YearMonth.now(ist).toString();

        /** All rows land "today", so they are in the current month whatever day the suite runs. */
        @BeforeEach
        void fixture() {
            sale(buyer, 100_000, 0, false, 0);          // sale ₹1000
            sale(otherBuyer, 50_000, 0, false, 0);      // sale ₹500
            sale(otherBuyer, 20_000, 0, true, 0);       // sale ₹200, refunded (same month) → SALE + REFUND lines
            jdbcTemplate.update("""
                    INSERT INTO creator_payouts (creator_id, amount_paise, status, gross_revenue_paise, platform_fee_paise,
                                                 net_payout_paise, payout_destination, payout_reference, processed_at)
                    VALUES (?, 30000, 'PAID', 0, 0, 30000, 'casey@upi', 'UTR9', NOW())
                    """, creator.getId());
        }

        @Test
        void totalsMatchTheFixture() {
            // sales gross 100,000 + 50,000 + 20,000; refund 20,000; fee 10,000+5,000+2,000 − 2,000
            asCreator.document("query($m: String!) { creatorStatement(month: $m) { month grossSalesPaise refundsPaise platformFeePaise netEarningsPaise payoutsPaise lines { type netPaise } } }")
                    .variable("m", month).execute()
                    .path("creatorStatement.grossSalesPaise").entity(Long.class).isEqualTo(170_000L)
                    .path("creatorStatement.refundsPaise").entity(Long.class).isEqualTo(20_000L)
                    .path("creatorStatement.platformFeePaise").entity(Long.class).isEqualTo(15_000L)
                    .path("creatorStatement.netEarningsPaise").entity(Long.class).isEqualTo(135_000L)   // 90,000+45,000+18,000−18,000
                    .path("creatorStatement.payoutsPaise").entity(Long.class).isEqualTo(30_000L)
                    .path("creatorStatement.lines").entityList(Object.class).hasSize(5);
        }

        @Test
        void aMonthWithNothingIsEmptyNotAnError() {
            asCreator.document("query { creatorStatement(month: \"2001-01\") { grossSalesPaise lines { type } } }")
                    .execute().path("creatorStatement.grossSalesPaise").entity(Long.class).isEqualTo(0L);
        }

        @Test
        void aMalformedMonthIsRefused() {
            expectCode(asCreator.document("query { creatorStatement(month: \"March\") { month } }"), "INVALID_INPUT");
        }

        @Test
        void csvContainsTheSameTotals() throws Exception {
            String csv = mockMvc.perform(MockMvcRequestBuilders.get("/api/creator/statements/" + month + ".csv")
                            .header("Authorization", "Bearer " + jwtService.generateAccessToken(creator)))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.parseMediaType("text/csv")))
                    .andReturn().getResponse().getContentAsString();
            assertThat(csv).startsWith("date,type,description,gross_paise,fee_paise,net_paise");
            assertThat(csv).contains(",TOTAL,Gross sales,170000,,");
            assertThat(csv).contains(",TOTAL,Refunds,-20000,,");
            assertThat(csv).contains(",TOTAL,Platform fee (net of refunds),,15000,");
            assertThat(csv).contains(",TOTAL,Net earnings,,,135000");
            assertThat(csv).contains(",TOTAL,Payouts,,,-30000");
        }

        @Test
        void csvIsForCreatorsOnly() throws Exception {
            String path = "/api/creator/statements/" + month + ".csv";
            // a signed-in non-creator is forbidden…
            mockMvc.perform(MockMvcRequestBuilders.get(path)
                            .header("Authorization", "Bearer " + jwtService.generateAccessToken(buyer)))
                    .andExpect(status().isForbidden());
            // …an anonymous caller is unauthenticated…
            mockMvc.perform(MockMvcRequestBuilders.get(path)).andExpect(status().isUnauthorized());
            // …and a DIFFERENT creator gets their own (empty) statement, never Casey's rows.
            User rival = makeUser("rival@example.com", "Rae Rival", Role.BUYER, Role.CREATOR);
            String csv = mockMvc.perform(MockMvcRequestBuilders.get(path)
                            .header("Authorization", "Bearer " + jwtService.generateAccessToken(rival)))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(csv).contains(",TOTAL,Gross sales,0,,").doesNotContain("Paid Guide");
        }
    }
}
