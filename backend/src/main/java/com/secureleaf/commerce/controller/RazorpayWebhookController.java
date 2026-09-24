package com.secureleaf.commerce.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.secureleaf.commerce.gateway.PaymentGatewayProperties;
import com.secureleaf.commerce.gateway.RazorpaySignatures;
import com.secureleaf.commerce.service.PaymentCompletionService;
import com.secureleaf.common.exception.BusinessException;
import com.secureleaf.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Receives Razorpay webhooks (D4, PAY-07). REST, not GraphQL: the caller is Razorpay's
 * server, which POSTs a fixed JSON shape to a URL — it doesn't speak GraphQL.
 *
 * AUTHENTICATION WITHOUT A LOGIN
 * This endpoint is permitAll in SecurityConfig — Razorpay has no JWT. Authenticity comes
 * from the HMAC in X-Razorpay-Signature instead: only someone holding our webhook secret can
 * produce it. Anything unsigned or wrongly signed is rejected before a single byte of the
 * body is trusted.
 *
 * RESPONSE CODES ARE INSTRUCTIONS TO THE SENDER
 * Webhook providers retry on any non-2xx response. So:
 *   - 2xx for "processed" AND for "duplicate" — a duplicate must stop the retries, not cause more;
 *   - 2xx for business rejections (unknown order, amount mismatch) — retrying can't fix them,
 *     they're logged as alerts for a human;
 *   - 400 for a bad signature / unreadable body;
 *   - 5xx (an unexpected exception) for OUR failures, e.g. database down — those SHOULD be
 *     retried, and idempotent processing makes the retry safe.
 */
@RestController
@RequestMapping("/api/webhooks")
@RequiredArgsConstructor
@Slf4j
public class RazorpayWebhookController {

    static final String EVENT_CAPTURED = "payment.captured";
    static final String EVENT_FAILED = "payment.failed";

    private final PaymentGatewayProperties gatewayProperties;
    private final PaymentCompletionService paymentCompletionService;
    private final ObjectMapper objectMapper;

    @PostMapping("/razorpay")
    public ResponseEntity<Void> handle(@RequestBody byte[] rawBody,
                                       @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature,
                                       @RequestHeader(value = "X-Razorpay-Event-Id", required = false) String eventId) {
        String expected = RazorpaySignatures.webhookSignature(rawBody, gatewayProperties.webhookSecret());
        if (!RazorpaySignatures.matches(expected, signature)) {
            log.warn("Rejected webhook with invalid signature (event id {})", eventId);
            return ResponseEntity.badRequest().build();
        }

        String body = new String(rawBody, StandardCharsets.UTF_8);
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (IOException e) {
            return ResponseEntity.badRequest().build();
        }

        String event = root.path("event").asText();
        JsonNode payment = root.path("payload").path("payment").path("entity");
        String gatewayOrderId = payment.path("order_id").asText(null);
        String gatewayPaymentId = payment.path("id").asText(null);

        try {
            switch (event) {
                case EVENT_CAPTURED -> paymentCompletionService.recordCapture(
                        gatewayOrderId, gatewayPaymentId, payment.path("amount").asLong(), eventId, body);
                case EVENT_FAILED -> paymentCompletionService.recordFailure(
                        gatewayOrderId, gatewayPaymentId,
                        payment.path("error_description").asText("Payment failed"), eventId, body);
                default -> log.info("Ignoring webhook event type '{}'", event);
            }
        } catch (ResourceNotFoundException e) {
            log.warn("Webhook {} for unknown order {} — acknowledged, not retried", eventId, gatewayOrderId);
        } catch (BusinessException e) {
            log.error("Webhook {} rejected ({}): {}", eventId, e.getErrorCode(), e.getMessage());
        } catch (DataIntegrityViolationException e) {
            // Final backstop: the UNIQUE provider_event_id (or the active-entitlement index)
            // caught a duplicate that slipped past the in-lock checks. Nothing to redo.
            log.info("Webhook {} hit a uniqueness constraint — treated as duplicate", eventId);
        }
        return ResponseEntity.ok().build();
    }
}
