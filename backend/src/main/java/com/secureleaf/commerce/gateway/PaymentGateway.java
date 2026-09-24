package com.secureleaf.commerce.gateway;

/**
 * Strategy interface for the payment provider (D1).
 *
 * Deliberately tiny: the only thing *our server* ever asks the gateway to do in the
 * Razorpay flow is create an order. Charging the card happens between the browser and the
 * gateway (checkout.js), and the result comes back to us two ways — the signed checkout
 * response and the signed webhook — both verified with {@link RazorpaySignatures}.
 *
 * Implementations: {@link MockRazorpayGateway} today; a {@code RazorpayGateway} that calls
 * {@code razorpayClient.orders.create(...)} when real payments are integrated. Nothing in
 * OrderService / PaymentCompletionService changes when that swap happens.
 */
public interface PaymentGateway {

    /** e.g. "MOCK_RAZORPAY" / "RAZORPAY" — stored in payments.provider_name for the audit trail. */
    String name();

    /** Public key id the browser needs to open checkout. */
    String keyId();

    /**
     * Creates the gateway-side order and returns its id ("order_XXXX").
     *
     * @param amountPaise amount in the smallest currency unit (Razorpay also takes paise)
     * @param receipt     our own reference, echoed back by the gateway ("sl_order_42")
     */
    String createOrder(long amountPaise, String currency, String receipt);
}
