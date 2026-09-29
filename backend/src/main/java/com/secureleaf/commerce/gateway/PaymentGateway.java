package com.secureleaf.commerce.gateway;

import java.util.List;
import java.util.Map;

/**
 * Strategy / port for the payment provider (Phase 4 D1, Phase 09B D2) — the "port" in
 * ports-and-adapters: OrderService, PaymentCompletionService and the refund/reconciliation code
 * depend on THIS interface and never on Razorpay's HTTP API.
 *
 * Charging the card happens between the browser and the gateway (checkout.js), and the result
 * comes back two ways — the signed checkout response and the signed webhook, both verified with
 * {@link RazorpaySignatures}. Everything else our server ever asks the gateway is on this
 * interface: create an order, look a payment up, capture an authorized one, refund a captured one.
 *
 * Implementations: {@link MockRazorpayGateway} (dev/tests, {@code provider=mock}) and
 * {@link RazorpayGateway} (real REST calls, {@code provider=razorpay}). Every failure of a real
 * call surfaces as a BusinessException(PAYMENT_GATEWAY_UNAVAILABLE) so callers have one thing
 * to handle.
 */
public interface PaymentGateway {

    /** e.g. "MOCK_RAZORPAY" / "RAZORPAY" — stored in payments.provider_name for the audit trail. */
    String name();

    /** Public key id the browser needs to open checkout. */
    String keyId();

    /** {@code MOCK} or {@code RAZORPAY} — what the frontend switches on (D3). */
    String provider();

    /**
     * Creates the gateway-side order and returns its id ("order_XXXX").
     *
     * @param amountPaise amount in the smallest currency unit (Razorpay also takes paise)
     * @param receipt     our own reference, echoed back by the gateway ("sl_order_42")
     */
    String createOrder(long amountPaise, String currency, String receipt);

    /** One payment: status, amount, fee, tax, method (D2). */
    GatewayPayment fetchPayment(String paymentId);

    /** Every payment attempt made against one gateway order (D2, used by reconciliation, D7). */
    List<GatewayPayment> fetchOrderPayments(String gatewayOrderId);

    /**
     * Captures an AUTHORIZED payment (D4). Idempotent on Razorpay's side: capturing an
     * already-captured payment is a harmless error we treat as success.
     */
    GatewayPayment capture(String paymentId, long amountPaise);

    /** Refunds {@code amountPaise} of a captured payment (D6). {@code notes} are echoed back by the gateway. */
    GatewayRefund refund(String paymentId, long amountPaise, Map<String, String> notes);
}
