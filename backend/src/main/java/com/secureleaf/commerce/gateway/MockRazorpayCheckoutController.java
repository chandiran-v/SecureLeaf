package com.secureleaf.commerce.gateway;

import com.secureleaf.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/**
 * Stand-in for "Razorpay's checkout popup + Razorpay's servers" (D1).
 *
 * In the real flow the browser talks to Razorpay directly — our backend never sees the card.
 * This controller is what the mock checkout page calls instead, and it answers the way
 * checkout.js would:
 * <ul>
 *   <li><b>SUCCESS</b> → {@code 200 {razorpay_order_id, razorpay_payment_id, razorpay_signature}}
 *       (what checkout.js passes to your {@code handler}) + a {@code payment.captured} webhook.</li>
 *   <li><b>DECLINE</b> → {@code 402} with a Razorpay-style error + a {@code payment.failed} webhook.</li>
 *   <li><b>TIMEOUT</b> → the money IS captured and the webhook IS sent (late), but the browser
 *       gets {@code 504}. The page must not assume failure: it polls the order until the webhook
 *       completes it (D13). This is the scenario idempotent webhooks exist for.</li>
 * </ul>
 *
 * Exists only when {@code payment.gateway.provider=mock} — it would be a "mark anything as paid"
 * endpoint in production. CommerceConfig refuses to start prod with the mock provider.
 */
@RestController
@RequestMapping("/api/mock-gateway")
@ConditionalOnProperty(name = "payment.gateway.provider", havingValue = "mock")
@RequiredArgsConstructor
@Slf4j
public class MockRazorpayCheckoutController {

    public enum Outcome { SUCCESS, DECLINE, TIMEOUT }

    public record PayRequest(Outcome outcome) {}

    static final String DECLINE_REASON = "Your card was declined by the issuing bank (mock).";

    private final MockRazorpayGateway gateway;
    private final MockWebhookSender webhookSender;
    private final PaymentGatewayProperties properties;

    @Value("${payment.mock.webhook-delay-ms:800}")
    private long webhookDelayMs;

    @Value("${payment.mock.timeout-webhook-delay-ms:4000}")
    private long timeoutWebhookDelayMs;

    @PostMapping("/orders/{gatewayOrderId}/pay")
    public ResponseEntity<Map<String, Object>> pay(@PathVariable String gatewayOrderId,
                                                   @RequestBody PayRequest request) {
        MockRazorpayGateway.MockOrder order = gateway.findOrder(gatewayOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("Gateway order", "id", gatewayOrderId));
        Outcome outcome = request.outcome() == null ? Outcome.SUCCESS : request.outcome();
        String paymentId = "pay_" + MockRazorpayGateway.randomId();
        log.info("[mock-gateway] {} for {} ({} paise), payment {}", outcome, gatewayOrderId, order.amountPaise(), paymentId);

        if (outcome == Outcome.DECLINE) {
            webhookSender.schedule(MockWebhookSender.EVENT_FAILED, order, paymentId, DECLINE_REASON,
                    Duration.ofMillis(webhookDelayMs));
            return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(Map.of("error", Map.of(
                    "code", "BAD_REQUEST_ERROR",
                    "description", DECLINE_REASON,
                    "reason", "payment_failed",
                    "metadata", Map.of("order_id", gatewayOrderId, "payment_id", paymentId))));
        }

        if (!gateway.markCaptured(gatewayOrderId)) {
            return ResponseEntity.badRequest().body(Map.of("error", Map.of(
                    "code", "BAD_REQUEST_ERROR",
                    "description", "Order has already been paid.")));
        }

        if (outcome == Outcome.TIMEOUT) {
            webhookSender.schedule(MockWebhookSender.EVENT_CAPTURED, order, paymentId, null,
                    Duration.ofMillis(timeoutWebhookDelayMs));
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(Map.of("error", Map.of(
                    "code", "GATEWAY_ERROR",
                    "description", "The payment gateway did not respond in time (mock). "
                            + "Your payment may still go through.")));
        }

        webhookSender.schedule(MockWebhookSender.EVENT_CAPTURED, order, paymentId, null,
                Duration.ofMillis(webhookDelayMs));
        return ResponseEntity.ok(Map.of(
                "razorpay_order_id", gatewayOrderId,
                "razorpay_payment_id", paymentId,
                "razorpay_signature",
                RazorpaySignatures.checkoutSignature(gatewayOrderId, paymentId, properties.keySecret())));
    }
}
