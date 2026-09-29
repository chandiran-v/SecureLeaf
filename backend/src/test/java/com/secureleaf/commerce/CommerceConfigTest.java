package com.secureleaf.commerce;

import com.secureleaf.commerce.gateway.PaymentGatewayProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase 09B D8 / acceptance criterion 7 — the startup guard, tested as a plain unit (no context). */
class CommerceConfigTest {

    private static CommerceConfig config(String provider, String keyId, boolean liveEnabled, String... profiles) {
        MockEnvironment env = new MockEnvironment();
        for (String p : profiles) env.addActiveProfile(p);
        return new CommerceConfig(
                new PaymentGatewayProperties(provider, keyId, "secret", "whsec"),
                new PaymentProperties(liveEnabled, null),
                env);
    }

    @Test
    void liveKey_withLiveNotEnabled_refusesToStart_withClearMessage() {
        assertThatThrownBy(() -> config("razorpay", "rzp_live_abc123", false).checkPaymentSetupIsSafe())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LIVE key")
                .hasMessageContaining("payment.live-enabled")
                .hasMessageNotContaining("abc123");   // never echo the key
    }

    @Test
    void liveKey_withLiveEnabled_starts() {
        assertThatCode(() -> config("razorpay", "rzp_live_abc123", true).checkPaymentSetupIsSafe()).doesNotThrowAnyException();
    }

    @Test
    void testKey_starts() {
        assertThatCode(() -> config("razorpay", "rzp_test_abc123", false).checkPaymentSetupIsSafe()).doesNotThrowAnyException();
    }

    @Test
    void mockProvider_isForbiddenInProd_butFineElsewhere() {
        assertThatThrownBy(() -> config("mock", "rzp_test_mock_key", false, "prod").checkPaymentSetupIsSafe())
                .hasMessageContaining("forbidden in the 'prod' profile");
        assertThatCode(() -> config("mock", "rzp_test_mock_key", false, "dev").checkPaymentSetupIsSafe()).doesNotThrowAnyException();
    }

    @Test
    void razorpayProvider_isAllowedInProd() {
        assertThatCode(() -> config("razorpay", "rzp_test_abc", false, "prod").checkPaymentSetupIsSafe()).doesNotThrowAnyException();
    }

    @Test
    void unknownProvider_refusesToStart() {
        assertThatThrownBy(() -> config("stripe", "k", false).checkPaymentSetupIsSafe())
                .hasMessageContaining("has no implementation");
    }
}
