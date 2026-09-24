package com.secureleaf.commerce.gateway;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PaymentGatewayProperties properties;
    private final Map<String, MockOrder> orders = new ConcurrentHashMap<>();
    private final Set<String> captured = ConcurrentHashMap.newKeySet();

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

    /** Razorpay-style 14-character id suffix ("pay_N3xk2...", "order_Mz9..."). */
    public static String randomId() {
        StringBuilder sb = new StringBuilder(14);
        for (int i = 0; i < 14; i++) sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return sb.toString();
    }
}
