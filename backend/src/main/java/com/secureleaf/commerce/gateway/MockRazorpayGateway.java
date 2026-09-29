package com.secureleaf.commerce.gateway;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import com.secureleaf.common.exception.BusinessException;
import com.secureleaf.common.exception.ErrorCode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Stand-in for Razorpay's Orders API (D1). Holds created orders in memory so the mock
 * checkout ({@link MockRazorpayCheckoutController}) can look up the amount when it "charges".
 *
 * In-memory is fine for a mock: after a restart, open checkouts can't be paid and the buyer
 * clicks Buy again — which, thanks to D2 layer 2, reuses their pending order id... but with a
 * gateway order id this map no longer knows. That exact edge case is why the real Razorpay
 * keeps orders server-side, and why this class is only a simulator.
 */
@Component
@ConditionalOnProperty(name = "payment.gateway.provider", havingValue = "mock")
public class MockRazorpayGateway implements PaymentGateway {

    public record MockOrder(String gatewayOrderId, long amountPaise, String currency, String receipt) {}

    /** A payment the mock "took". Mutable status: authorized → captured → refunded. */
    public static final class MockPayment {
        final String id;
        final String orderId;
        final long amountPaise;
        volatile String status;

        MockPayment(String id, String orderId, long amountPaise, String status) {
            this.id = id;
            this.orderId = orderId;
            this.amountPaise = amountPaise;
            this.status = status;
        }
    }

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PaymentGatewayProperties properties;
    private final Map<String, MockOrder> orders = new ConcurrentHashMap<>();
    private final Set<String> captured = ConcurrentHashMap.newKeySet();
    private final Map<String, MockPayment> payments = new ConcurrentHashMap<>();
    private final List<String> captureCalls = new CopyOnWriteArrayList<>();
    private final List<String> refundCalls = new CopyOnWriteArrayList<>();

    public MockRazorpayGateway(PaymentGatewayProperties properties) {
        this.properties = properties;
    }

    @Override
    public String name() {
        return "MOCK_RAZORPAY";
    }

    @Override
    public String keyId() {
        return properties.keyId();
    }

    @Override
    public String provider() {
        return "MOCK";
    }

    @Override
    public String createOrder(long amountPaise, String currency, String receipt) {
        String id = "order_" + randomId();
        orders.put(id, new MockOrder(id, amountPaise, currency, receipt));
        return id;
    }

    public Optional<MockOrder> findOrder(String gatewayOrderId) {
        return Optional.ofNullable(orders.get(gatewayOrderId));
    }

    /**
     * Like Razorpay, an order can be captured at most once. Returns false if it already was.
     * Set.add is atomic on a concurrent set, so two simultaneous "pay" clicks can't both win.
     */
    public boolean markCaptured(String gatewayOrderId) {
        return captured.add(gatewayOrderId);
    }

    /**
     * The mock checkout took a payment for this order. {@code status} is "captured" (auto-capture
     * account) or "authorized" (capture still to come — D4).
     */
    public void registerPayment(String gatewayOrderId, String paymentId, String status) {
        MockOrder order = orders.get(gatewayOrderId);
        if (order == null) throw new IllegalArgumentException("Unknown mock order " + gatewayOrderId);
        payments.put(paymentId, new MockPayment(paymentId, gatewayOrderId, order.amountPaise(), status));
    }

    @Override
    public GatewayPayment fetchPayment(String paymentId) {
        return toGatewayPayment(requirePayment(paymentId));
    }

    @Override
    public List<GatewayPayment> fetchOrderPayments(String gatewayOrderId) {
        List<GatewayPayment> result = new ArrayList<>();
        payments.values().stream()
                .filter(p -> p.orderId.equals(gatewayOrderId))
                .forEach(p -> result.add(toGatewayPayment(p)));
        return result;
    }

    @Override
    public synchronized GatewayPayment capture(String paymentId, long amountPaise) {
        MockPayment payment = requirePayment(paymentId);
        captureCalls.add(paymentId);
        if ("authorized".equals(payment.status)) {
            if (amountPaise != payment.amountPaise) {
                throw new BusinessException(ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE, "Capture amount must equal the payment amount (mock).");
            }
            payment.status = "captured";
            captured.add(payment.orderId);
        }
        return toGatewayPayment(payment);   // already captured → idempotent, like the real one
    }

    @Override
    public synchronized GatewayRefund refund(String paymentId, long amountPaise, Map<String, String> notes) {
        MockPayment payment = requirePayment(paymentId);
        if (!"captured".equals(payment.status) || amountPaise != payment.amountPaise) {
            throw new BusinessException(ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE,
                    "Only a captured payment can be fully refunded (mock).");
        }
        payment.status = "refunded";
        refundCalls.add(paymentId);
        return new GatewayRefund("rfnd_" + randomId(), paymentId, amountPaise, "processed");
    }

    /** Test/inspection hooks: which payment ids were captured / refunded through the API. */
    public List<String> captureCalls() {
        return List.copyOf(captureCalls);
    }

    public List<String> refundCalls() {
        return List.copyOf(refundCalls);
    }

    /** Razorpay's real pricing shape: 2% fee plus 18% GST on the fee, rounded half-up. */
    public static long feeFor(long amountPaise) {
        return (amountPaise * 2 + 50) / 100;
    }

    public static long taxOnFee(long feePaise) {
        return (feePaise * 18 + 50) / 100;
    }

    private MockPayment requirePayment(String paymentId) {
        MockPayment p = payments.get(paymentId);
        if (p == null) {
            throw new BusinessException(ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE, "No such payment (mock): " + paymentId);
        }
        return p;
    }

    private static GatewayPayment toGatewayPayment(MockPayment p) {
        boolean charged = "captured".equals(p.status) || "refunded".equals(p.status);
        Long fee = charged ? feeFor(p.amountPaise) : null;
        Long tax = charged ? taxOnFee(fee) : null;
        return new GatewayPayment(p.id, p.orderId, p.status, p.amountPaise, fee, tax, "card");
    }

    /** Razorpay-style 14-character id suffix ("pay_N3xk2...", "order_Mz9..."). */
    public static String randomId() {
        StringBuilder sb = new StringBuilder(14);
        for (int i = 0; i < 14; i++) sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return sb.toString();
    }
}
