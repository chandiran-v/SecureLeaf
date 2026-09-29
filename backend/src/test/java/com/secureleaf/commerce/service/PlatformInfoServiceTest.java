package com.secureleaf.commerce.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 09B D8 — payment mode is derived from provider + key prefix. */
class PlatformInfoServiceTest {

    @Test
    void mockProvider_isMock_whateverTheKeyLooksLike() {
        assertThat(PlatformInfoService.paymentMode("MOCK", "rzp_test_mock_key")).isEqualTo("MOCK");
        assertThat(PlatformInfoService.paymentMode("MOCK", "rzp_live_x")).isEqualTo("MOCK");
    }

    @Test
    void razorpayWithTestKey_isTest() {
        assertThat(PlatformInfoService.paymentMode("RAZORPAY", "rzp_test_abc")).isEqualTo("TEST");
    }

    @Test
    void razorpayWithLiveKey_isLive() {
        assertThat(PlatformInfoService.paymentMode("RAZORPAY", "rzp_live_abc")).isEqualTo("LIVE");
    }
}
