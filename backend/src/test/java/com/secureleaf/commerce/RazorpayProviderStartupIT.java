package com.secureleaf.commerce;

import com.secureleaf.AbstractIntegrationTest;
import com.secureleaf.commerce.gateway.PaymentGateway;
import com.secureleaf.commerce.gateway.RazorpayGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 09B acceptance criterion 7 — the application really boots with {@code provider=razorpay}
 * and a {@code rzp_test_} key (so the real gateway bean is wired and the startup guard accepts it),
 * and the public {@code platformInfo} query reports {@code TEST}. The refusal of a {@code rzp_live_}
 * key is covered by CommerceConfigTest.
 */
@TestPropertySource(properties = {
        "payment.gateway.provider=razorpay",
        "payment.gateway.key-id=rzp_test_startup123",
        "payment.gateway.key-secret=startup-secret",
        "payment.gateway.webhook-secret=startup-webhook-secret",
        "processing.worker.enabled=false"
})
class RazorpayProviderStartupIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private PaymentGateway paymentGateway;

    @Test
    void razorpayProvider_wiresTheRealGateway() {
        assertThat(paymentGateway).isInstanceOf(RazorpayGateway.class);
        assertThat(paymentGateway.keyId()).isEqualTo("rzp_test_startup123");
    }

    @Test
    void platformInfo_reportsTestMode_toAnonymousVisitors() throws Exception {
        mockMvc.perform(post("/graphql").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"{ platformInfo { paymentMode supportEmail } }\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"paymentMode\":\"TEST\"")));
    }

    @Test
    void theMockCheckoutEndpoint_doesNotExistWithTheRealGateway() throws Exception {
        mockMvc.perform(post("/api/mock-gateway/orders/order_x/pay").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is4xxClientError());
    }
}
