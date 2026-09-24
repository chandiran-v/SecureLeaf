package com.secureleaf.commerce.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Plays the part of Razorpay's webhook delivery system (D1, D4, D13).
 *
 * Builds event bodies in Razorpay's exact JSON shape, signs them with the webhook secret
 * ({@code X-Razorpay-Signature}), and POSTs them to our own {@code /api/webhooks/razorpay} over
 * real HTTP — so the webhook controller, signature check and dedup logic are exercised exactly
 * as they will be with the real gateway.
 *
 * It also reproduces the two behaviours of real webhook systems that bite people in production:
 * <ul>
 *   <li><b>Delay</b> — the webhook arrives after the browser's response (or, in the TIMEOUT
 *       scenario, instead of it).</li>
 *   <li><b>Duplicates</b> — delivery is "at least once": with {@code payment.mock.duplicate-webhooks}
 *       on, every event is sent twice with the same event id, and our handler must shrug off
 *       the second copy.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "payment.gateway.provider", havingValue = "mock")
@Slf4j
public class MockWebhookSender {

    public static final String EVENT_CAPTURED = "payment.captured";
    public static final String EVENT_FAILED = "payment.failed";

    private final PaymentGatewayProperties properties;
    private final ObjectMapper objectMapper;
    private final Environment environment;
    private final RestClient restClient = RestClient.create();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, runnable -> {
        Thread t = new Thread(runnable, "mock-webhook");
        t.setDaemon(true);
        return t;
    });

    @Value("${payment.mock.webhooks-enabled:true}")
    private boolean enabled;

    @Value("${payment.mock.duplicate-webhooks:true}")
    private boolean sendDuplicates;

    @Value("${payment.mock.webhook-url:}")
    private String configuredWebhookUrl;

    public MockWebhookSender(PaymentGatewayProperties properties, ObjectMapper objectMapper, Environment environment) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.environment = environment;
    }

    /** Schedules delivery of one event (plus a duplicate copy, if enabled). */
    public void schedule(String event, MockRazorpayGateway.MockOrder order, String paymentId,
                         String errorDescription, Duration delay) {
        if (!enabled) {
            log.debug("Mock webhooks disabled — not sending {} for {}", event, order.gatewayOrderId());
            return;
        }
        String eventId = "evt_" + MockRazorpayGateway.randomId();
        String body = buildEventBody(event, order.gatewayOrderId(), paymentId, order.amountPaise(),
                order.currency(), errorDescription);

        scheduler.schedule(() -> deliver(body, eventId), delay.toMillis(), TimeUnit.MILLISECONDS);
        if (sendDuplicates) {
            // Same event id — exactly what a real provider's retry looks like.
            scheduler.schedule(() -> deliver(body, eventId), delay.toMillis() + 400, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * A Razorpay-format webhook body. Public so tests can build and sign the same payloads
     * the mock sends, and post them deterministically.
     */
    public String buildEventBody(String event, String gatewayOrderId, String paymentId, long amountPaise,
                                 String currency, String errorDescription) {
        Map<String, Object> payment = new LinkedHashMap<>();
        payment.put("id", paymentId);
        payment.put("entity", "payment");
        payment.put("amount", amountPaise);
        payment.put("currency", currency);
        payment.put("status", EVENT_CAPTURED.equals(event) ? "captured" : "failed");
        payment.put("order_id", gatewayOrderId);
        payment.put("method", "card");
        payment.put("error_code", errorDescription == null ? null : "BAD_REQUEST_ERROR");
        payment.put("error_description", errorDescription);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("entity", "event");
        root.put("account_id", "acc_mock");
        root.put("event", event);
        root.put("contains", List.of("payment"));
        root.put("payload", Map.of("payment", Map.of("entity", payment)));
        root.put("created_at", Instant.now().getEpochSecond());
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private void deliver(String body, String eventId) {
        String signature = RazorpaySignatures.webhookSignature(body, properties.webhookSecret());
        try {
            restClient.post()
                    .uri(webhookUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Razorpay-Signature", signature)
                    .header("X-Razorpay-Event-Id", eventId)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.info("[mock-gateway] delivered webhook {}", eventId);
        } catch (Exception e) {
            // A real provider would retry with backoff for ~24h. The mock just logs.
            log.warn("[mock-gateway] webhook {} delivery failed: {}", eventId, e.getMessage());
        }
    }

    private String webhookUrl() {
        if (configuredWebhookUrl != null && !configuredWebhookUrl.isBlank()) return configuredWebhookUrl;
        String port = environment.getProperty("local.server.port", environment.getProperty("server.port", "8080"));
        return "http://localhost:" + port + "/api/webhooks/razorpay";
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
