package com.secureleaf.commerce.gateway;

import com.secureleaf.common.exception.BusinessException;
import com.secureleaf.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Phase 09B acceptance criteria 1 and 2 — the HTTP client tested WITHOUT a network: MockRestServiceServer
 * replaces the transport, so each test asserts the exact request we send (method, path, Basic auth,
 * body) and feeds back Razorpay-shaped responses. No Spring context needed.
 */
class RazorpayGatewayTest {

    private static final String KEY_ID = "rzp_test_abc123";
    private static final String KEY_SECRET = "s3cr3t";
    private static final String BASE = "https://api.razorpay.com/v1";
    private static final String BASIC = "Basic " + Base64.getEncoder().encodeToString((KEY_ID + ":" + KEY_SECRET).getBytes());

    private MockRestServiceServer server;
    private RazorpayGateway gateway;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        gateway = new RazorpayGateway(new PaymentGatewayProperties("razorpay", KEY_ID, KEY_SECRET, "wh"), builder.build());
    }

    // ── createOrder ──────────────────────────────────────────────────────────

    @Test
    void createOrder_sendsCorrectRequest_andParsesId() {
        server.expect(requestTo(BASE + "/orders"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", BASIC))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.amount").value(49900))          // paise, an integer
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.receipt").value("sl_order_42"))
                .andExpect(jsonPath("$.notes.orderId").value("42"))
                .andRespond(withSuccess("{\"id\":\"order_XYZ\",\"amount\":49900,\"status\":\"created\"}", MediaType.APPLICATION_JSON));

        assertThat(gateway.createOrder(49_900, "INR", "sl_order_42")).isEqualTo("order_XYZ");
        server.verify();
    }

    @Test
    void createOrder_4xx_becomesGatewayUnavailable() {
        server.expect(requestTo(BASE + "/orders")).andRespond(withStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
                .body("{\"error\":{\"description\":\"Authentication failed\"}}").contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gateway.createOrder(100, "INR", "sl_order_1"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE))
                .hasMessageNotContaining(KEY_SECRET);
    }

    @Test
    void createOrder_5xx_becomesGatewayUnavailable() {
        server.expect(requestTo(BASE + "/orders")).andRespond(withServerError());

        assertThatThrownBy(() -> gateway.createOrder(100, "INR", "sl_order_1"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE));
    }

    @Test
    void createOrder_responseWithoutId_isRejected() {
        server.expect(requestTo(BASE + "/orders")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gateway.createOrder(100, "INR", "sl_order_1")).isInstanceOf(BusinessException.class);
    }

    // ── fetchPayment ─────────────────────────────────────────────────────────

    @Test
    void fetchPayment_parsesStatusAmountFeeTaxAndMethod() {
        server.expect(requestTo(BASE + "/payments/pay_1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", BASIC))
                .andRespond(withSuccess("""
                        {"id":"pay_1","order_id":"order_1","status":"captured","amount":49900,
                         "fee":998,"tax":180,"method":"upi"}""", MediaType.APPLICATION_JSON));

        GatewayPayment payment = gateway.fetchPayment("pay_1");

        assertThat(payment.id()).isEqualTo("pay_1");
        assertThat(payment.orderId()).isEqualTo("order_1");
        assertThat(payment.isCaptured()).isTrue();
        assertThat(payment.amountPaise()).isEqualTo(49_900);
        assertThat(payment.feePaise()).isEqualTo(998);
        assertThat(payment.taxPaise()).isEqualTo(180);
        assertThat(payment.method()).isEqualTo("upi");
    }

    @Test
    void fetchPayment_beforeCapture_hasNullFee() {
        server.expect(requestTo(BASE + "/payments/pay_1")).andRespond(withSuccess(
                "{\"id\":\"pay_1\",\"status\":\"authorized\",\"amount\":100,\"fee\":null,\"tax\":null}", MediaType.APPLICATION_JSON));

        GatewayPayment payment = gateway.fetchPayment("pay_1");

        assertThat(payment.isAuthorized()).isTrue();
        assertThat(payment.feePaise()).isNull();
    }

    @Test
    void fetchPayment_notFound_becomesGatewayUnavailable() {
        server.expect(requestTo(BASE + "/payments/pay_x")).andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> gateway.fetchPayment("pay_x")).isInstanceOf(BusinessException.class);
    }

    // ── fetchOrderPayments ───────────────────────────────────────────────────

    @Test
    void fetchOrderPayments_parsesEveryAttempt() {
        server.expect(requestTo(BASE + "/orders/order_1/payments"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"entity":"collection","count":2,"items":[
                          {"id":"pay_a","order_id":"order_1","status":"failed","amount":100},
                          {"id":"pay_b","order_id":"order_1","status":"captured","amount":100,"fee":2,"tax":0}]}""",
                        MediaType.APPLICATION_JSON));

        List<GatewayPayment> attempts = gateway.fetchOrderPayments("order_1");

        assertThat(attempts).extracting(GatewayPayment::id).containsExactly("pay_a", "pay_b");
        assertThat(attempts).extracting(GatewayPayment::status).containsExactly("failed", "captured");
    }

    @Test
    void fetchOrderPayments_serverError_becomesGatewayUnavailable() {
        server.expect(requestTo(BASE + "/orders/order_1/payments")).andRespond(withServerError());

        assertThatThrownBy(() -> gateway.fetchOrderPayments("order_1")).isInstanceOf(BusinessException.class);
    }

    // ── capture ──────────────────────────────────────────────────────────────

    @Test
    void capture_postsAmountAndCurrency() {
        server.expect(requestTo(BASE + "/payments/pay_1/capture"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", BASIC))
                .andExpect(jsonPath("$.amount").value(49900))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andRespond(withSuccess("{\"id\":\"pay_1\",\"status\":\"captured\",\"amount\":49900,\"fee\":998,\"tax\":180}",
                        MediaType.APPLICATION_JSON));

        assertThat(gateway.capture("pay_1", 49_900).isCaptured()).isTrue();
        server.verify();
    }

    @Test
    void capture_alreadyCaptured_isTreatedAsSuccess() {
        server.expect(requestTo(BASE + "/payments/pay_1/capture")).andRespond(withStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
                .body("{\"error\":{\"description\":\"This payment has already been captured\"}}").contentType(MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/payments/pay_1")).andRespond(withSuccess(
                "{\"id\":\"pay_1\",\"status\":\"captured\",\"amount\":49900}", MediaType.APPLICATION_JSON));

        assertThat(gateway.capture("pay_1", 49_900).isCaptured()).isTrue();
    }

    @Test
    void capture_otherRejection_becomesGatewayUnavailable() {
        server.expect(requestTo(BASE + "/payments/pay_1/capture")).andRespond(withStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
                .body("{\"error\":{\"description\":\"amount mismatch\"}}").contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gateway.capture("pay_1", 1)).isInstanceOf(BusinessException.class);
    }

    // ── refund ───────────────────────────────────────────────────────────────

    @Test
    void refund_postsAmountAndNotes_andParsesRefund() {
        server.expect(requestTo(BASE + "/payments/pay_1/refund"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", BASIC))
                .andExpect(jsonPath("$.amount").value(49900))
                .andExpect(jsonPath("$.notes.orderId").value("42"))
                .andRespond(withSuccess("{\"id\":\"rfnd_9\",\"payment_id\":\"pay_1\",\"amount\":49900,\"status\":\"processed\"}",
                        MediaType.APPLICATION_JSON));

        GatewayRefund refund = gateway.refund("pay_1", 49_900, Map.of("orderId", "42"));

        assertThat(refund.id()).isEqualTo("rfnd_9");
        assertThat(refund.paymentId()).isEqualTo("pay_1");
        assertThat(refund.amountPaise()).isEqualTo(49_900);
        assertThat(refund.status()).isEqualTo("processed");
    }

    @Test
    void refund_rejected_becomesGatewayUnavailable() {
        server.expect(requestTo(BASE + "/payments/pay_1/refund")).andRespond(withStatus(org.springframework.http.HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> gateway.refund("pay_1", 1, Map.of()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE));
    }

    @Test
    void identity_isRazorpay() {
        assertThat(gateway.name()).isEqualTo("RAZORPAY");
        assertThat(gateway.provider()).isEqualTo("RAZORPAY");
        assertThat(gateway.keyId()).isEqualTo(KEY_ID);
    }
}
