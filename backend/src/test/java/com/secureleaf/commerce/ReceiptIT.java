package com.secureleaf.commerce;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 09C D5 — the receipt is owner-only and masks the gateway payment id. */
class ReceiptIT extends AbstractCommerceIT {

    private static final String RECEIPT = """
            query($id: ID!) { orderReceipt(orderId: $id) { orderId status productTitle creatorName amountPaise paymentIdMasked paymentMode } }
            """;

    private long completedOrder() {
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO orders (buyer_id, total_amount_paise, status, completed_at)
                VALUES (?, ?, 'COMPLETED', NOW()) RETURNING id
                """, Long.class, buyer.getId(), PRICE_PAISE);
        jdbcTemplate.update("INSERT INTO order_items (order_id, product_id, price_paise, platform_fee_paise, creator_earnings_paise) VALUES (?,?,?,?,?)",
                id, paidProduct.getId(), PRICE_PAISE, 4990, PRICE_PAISE - 4990);
        jdbcTemplate.update("INSERT INTO payments (order_id, idempotency_key, provider_payment_id, provider_name, amount_paise, status) VALUES (?,?,?,?,?,'COMPLETED')",
                id, "rk-" + id, "pay_SECRETPAYMENT9876", "RAZORPAY", PRICE_PAISE);
        return id;
    }

    @Test
    void ownerSeesTheReceiptWithAMaskedPaymentId() {
        long id = completedOrder();
        asBuyer.document(RECEIPT).variable("id", id).execute()
                .path("orderReceipt.productTitle").entity(String.class).isEqualTo("Paid Guide")
                .path("orderReceipt.creatorName").entity(String.class).isEqualTo("Casey Creator")
                .path("orderReceipt.amountPaise").entity(Long.class).isEqualTo(PRICE_PAISE)
                .path("orderReceipt.paymentIdMasked").entity(String.class).isEqualTo("pay_••••9876")
                .path("orderReceipt.paymentMode").entity(String.class).isEqualTo("MOCK");
    }

    @Test
    void anotherBuyerGetsNotFound() {
        long id = completedOrder();
        asOtherBuyer.document(RECEIPT).variable("id", id).execute().errors()
                .expect(e -> "NOT_FOUND".equals(e.getExtensions().get("code"))).verify();
    }

    @Test
    void anAnonymousCallerIsDenied() {
        long id = completedOrder();
        anonymous.document(RECEIPT).variable("id", id).execute().errors()
                .expect(e -> e.getExtensions().get("code") != null).verify();
    }

    @Test
    void anUnpaidOrderHasNoReceipt() {
        Long id = jdbcTemplate.queryForObject("INSERT INTO orders (buyer_id, total_amount_paise) VALUES (?, ?) RETURNING id", Long.class,
                buyer.getId(), PRICE_PAISE);
        jdbcTemplate.update("INSERT INTO order_items (order_id, product_id, price_paise, platform_fee_paise, creator_earnings_paise) VALUES (?,?,?,?,?)",
                id, paidProduct.getId(), PRICE_PAISE, 4990, PRICE_PAISE - 4990);
        asBuyer.document(RECEIPT).variable("id", id).execute().errors()
                .expect(e -> "NOT_FOUND".equals(e.getExtensions().get("code"))).verify();
        assertThat(id).isPositive();
    }
}
