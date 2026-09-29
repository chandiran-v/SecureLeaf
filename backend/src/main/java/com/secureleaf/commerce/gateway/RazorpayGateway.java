package com.secureleaf.commerce.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.secureleaf.common.exception.BusinessException;
import com.secureleaf.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The real Razorpay adapter (Phase 09B D1) — {@code provider=razorpay}. Talks to Razorpay's REST
 * API with Spring's {@link RestClient} and HTTP Basic auth ({@code key_id:key_secret}).
 *
 * WHY NO RAZORPAY SDK
 * Five endpoints don't justify a dependency: fewer moving parts on a small ARM server, no SDK
 * upgrade treadmill, and the whole client is testable with {@code MockRestServiceServer} by
 * asserting the exact HTTP request we send (see RazorpayGatewayTest).
 *
 * ERRORS
 * Any 4xx/5xx, timeout or connection failure becomes {@code BusinessException(PAYMENT_GATEWAY_UNAVAILABLE)}.
 * Callers therefore have one failure to think about, and — because createOrder is the first thing
 * initiateOrder does after inserting the order row — the surrounding transaction rolls back, so a
 * failed gateway call never leaves a half-created order behind. The key secret is never logged.
 */
@Slf4j
public class RazorpayGateway implements PaymentGateway {

    static final String CURRENCY = "INR";

    private final PaymentGatewayProperties properties;
    private final RestClient restClient;

    public RazorpayGateway(PaymentGatewayProperties properties, RestClient restClient) {
        this.properties = properties;
        this.restClient = restClient;
    }

    @Override
    public String name() {
        return "RAZORPAY";
    }

    @Override
    public String keyId() {
        return properties.keyId();
    }

    @Override
    public String provider() {
        return "RAZORPAY";
    }

    @Override
    public String createOrder(long amountPaise, String currency, String receipt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", amountPaise);
        body.put("currency", currency);
        body.put("receipt", receipt);
        // The receipt is "sl_order_<id>": echo the id in notes too so it's visible in the dashboard.
        body.put("notes", Map.of("orderId", receipt.replaceFirst("^sl_order_", "")));

        JsonNode response = call("create order", () -> restClient.post()
                .uri(url("/orders"))
                .headers(h -> h.setBasicAuth(properties.keyId(), properties.keySecret()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class));
        String id = text(response, "id");
        if (id == null) {
            throw unavailable("create order: response had no order id", null);
        }
        return id;
    }

    @Override
    public GatewayPayment fetchPayment(String paymentId) {
        JsonNode response = call("fetch payment", () -> restClient.get()
                .uri(url("/payments/" + paymentId))
                .headers(h -> h.setBasicAuth(properties.keyId(), properties.keySecret()))
                .retrieve()
                .body(JsonNode.class));
        return toPayment(response);
    }

    @Override
    public List<GatewayPayment> fetchOrderPayments(String gatewayOrderId) {
        JsonNode response = call("fetch order payments", () -> restClient.get()
                .uri(url("/orders/" + gatewayOrderId + "/payments"))
                .headers(h -> h.setBasicAuth(properties.keyId(), properties.keySecret()))
                .retrieve()
                .body(JsonNode.class));
        List<GatewayPayment> payments = new ArrayList<>();
        if (response != null) {
            for (JsonNode item : response.path("items")) {
                payments.add(toPayment(item));
            }
        }
        return payments;
    }

    @Override
    public GatewayPayment capture(String paymentId, long amountPaise) {
        Map<String, Object> body = Map.of("amount", amountPaise, "currency", CURRENCY);
        try {
            JsonNode response = call("capture payment", () -> restClient.post()
                    .uri(url("/payments/" + paymentId + "/capture"))
                    .headers(h -> h.setBasicAuth(properties.keyId(), properties.keySecret()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class));
            return toPayment(response);
        } catch (BusinessException e) {
            // Idempotent on Razorpay's side (D4): capturing twice is an error there. If the
            // payment is in fact already captured, that's the outcome we wanted.
            if (e.getCause() instanceof RestClientResponseException rre
                    && rre.getResponseBodyAsString().contains("already been captured")) {
                return fetchPayment(paymentId);
            }
            throw e;
        }
    }

    @Override
    public GatewayRefund refund(String paymentId, long amountPaise, Map<String, String> notes) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", amountPaise);
        if (notes != null && !notes.isEmpty()) body.put("notes", notes);

        JsonNode response = call("refund payment", () -> restClient.post()
                .uri(url("/payments/" + paymentId + "/refund"))
                .headers(h -> h.setBasicAuth(properties.keyId(), properties.keySecret()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class));
        String id = text(response, "id");
        if (id == null) {
            throw unavailable("refund payment: response had no refund id", null);
        }
        return new GatewayRefund(id, paymentId, response.path("amount").asLong(amountPaise),
                response.path("status").asText("processed"));
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private String url(String path) {
        return properties.baseUrl() + path;
    }

    private static GatewayPayment toPayment(JsonNode n) {
        if (n == null || n.path("id").isMissingNode()) {
            throw unavailable("payment response had no id", null);
        }
        return new GatewayPayment(
                n.path("id").asText(),
                text(n, "order_id"),
                n.path("status").asText(),
                n.path("amount").asLong(),
                n.hasNonNull("fee") ? n.get("fee").asLong() : null,
                n.hasNonNull("tax") ? n.get("tax").asLong() : null,
                text(n, "method"));
    }

    private static String text(JsonNode n, String field) {
        return n != null && n.hasNonNull(field) ? n.get(field).asText() : null;
    }

    /** Runs one HTTP call and converts every failure into the one exception callers handle. */
    private static JsonNode call(String what, Supplier<JsonNode> request) {
        try {
            return request.get();
        } catch (RestClientResponseException e) {
            // Log status only — the body of an error is safe, but there's no reason to log a request.
            log.warn("Razorpay {} failed: HTTP {} {}", what, e.getStatusCode().value(), e.getResponseBodyAsString());
            throw unavailable(what + " was rejected (HTTP " + e.getStatusCode().value() + ")", e);
        } catch (RestClientException e) {
            log.warn("Razorpay {} failed: {}", what, e.getMessage());
            throw unavailable(what + " could not reach Razorpay", e);
        }
    }

    private static BusinessException unavailable(String detail, Throwable cause) {
        log.debug("Gateway problem: {}", detail);
        return new BusinessException(ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE,
                "The payment provider is unavailable right now — please try again in a moment.", cause);
    }
}
