package com.secureleaf.commerce.gateway;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 09B acceptance criterion 3. The expected values below were computed independently
 * (Python's hmac module), NOT by calling the code under test — so a bug in RazorpaySignatures
 * can't make its own test pass.
 */
class RazorpaySignaturesTest {

    // HMAC_SHA256("order_DBJOWzybf0sJbb|pay_DBJPxQy1kKdlqc", "test_key_secret")
    private static final String CHECKOUT_VECTOR = "fd7ce383ef40ceb613667c2cbdda6b4912b814ccb8c6a4dfe85049f14e05a6ce";
    private static final String WEBHOOK_BODY = "{\"event\":\"payment.captured\",\"payload\":{}}";
    // HMAC_SHA256(WEBHOOK_BODY, "whsec_test")
    private static final String WEBHOOK_VECTOR = "09873f612c535c8d3b2de754fd0befb1c0efac0ffcbea2c60de522e3d4ba045c";

    @Test
    void checkoutSignature_matchesKnownGoodVector() {
        String actual = RazorpaySignatures.checkoutSignature("order_DBJOWzybf0sJbb", "pay_DBJPxQy1kKdlqc", "test_key_secret");

        assertThat(actual).isEqualTo(CHECKOUT_VECTOR);
        assertThat(RazorpaySignatures.matches(CHECKOUT_VECTOR, actual)).isTrue();
    }

    @Test
    void checkoutSignature_tamperedInputsOrSecretFail() {
        assertThat(RazorpaySignatures.matches(CHECKOUT_VECTOR,
                RazorpaySignatures.checkoutSignature("order_DBJOWzybf0sJbb", "pay_OTHER", "test_key_secret"))).isFalse();
        assertThat(RazorpaySignatures.matches(CHECKOUT_VECTOR,
                RazorpaySignatures.checkoutSignature("order_OTHER", "pay_DBJPxQy1kKdlqc", "test_key_secret"))).isFalse();
        assertThat(RazorpaySignatures.matches(CHECKOUT_VECTOR,
                RazorpaySignatures.checkoutSignature("order_DBJOWzybf0sJbb", "pay_DBJPxQy1kKdlqc", "wrong_secret"))).isFalse();
    }

    @Test
    void webhookSignature_matchesKnownGoodVector() {
        assertThat(RazorpaySignatures.webhookSignature(WEBHOOK_BODY.getBytes(StandardCharsets.UTF_8), "whsec_test"))
                .isEqualTo(WEBHOOK_VECTOR);
        assertThat(RazorpaySignatures.webhookSignature(WEBHOOK_BODY, "whsec_test")).isEqualTo(WEBHOOK_VECTOR);
    }

    @Test
    void webhookSignature_tamperedBodyOrHeaderFails() {
        String tamperedBody = WEBHOOK_BODY.replace("captured", "refunded");
        assertThat(RazorpaySignatures.matches(WEBHOOK_VECTOR, RazorpaySignatures.webhookSignature(tamperedBody, "whsec_test"))).isFalse();
        assertThat(RazorpaySignatures.matches(WEBHOOK_VECTOR, WEBHOOK_VECTOR.replace('0', '1'))).isFalse();
        assertThat(RazorpaySignatures.matches(WEBHOOK_VECTOR, null)).isFalse();
        assertThat(RazorpaySignatures.matches(WEBHOOK_VECTOR, "")).isFalse();
    }
}
