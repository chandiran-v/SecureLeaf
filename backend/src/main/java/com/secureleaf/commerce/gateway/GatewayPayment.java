package com.secureleaf.commerce.gateway;

/**
 * A payment as the gateway reports it (D2) — only the fields we act on.
 *
 * @param status      Razorpay's lifecycle word, lower-case: created | authorized | captured | refunded | failed
 * @param amountPaise what was charged, in paise
 * @param feePaise    what the gateway charges US for this payment (null until it is captured)
 * @param taxPaise    GST on that fee (null until it is captured)
 * @param method      card | upi | netbanking | wallet …
 */
public record GatewayPayment(String id, String orderId, String status, long amountPaise,
                             Long feePaise, Long taxPaise, String method) {

    public boolean isCaptured() {
        return "captured".equals(status);
    }

    public boolean isAuthorized() {
        return "authorized".equals(status);
    }
}
