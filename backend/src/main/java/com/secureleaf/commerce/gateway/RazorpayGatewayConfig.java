package com.secureleaf.commerce.gateway;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Wires the real {@link RazorpayGateway} (Phase 09B D1) when {@code payment.gateway.provider=razorpay}.
 * Kept out of the gateway class itself so a test can hand the gateway a RestClient bound to
 * {@code MockRestServiceServer} without fighting the timeout setup here.
 *
 * Timeouts: connect 3 s, read 10 s. An HTTP call with no timeout can hang a request thread
 * forever when the remote end stalls — and checkout would hang with it.
 */
@Configuration
@ConditionalOnProperty(name = "payment.gateway.provider", havingValue = "razorpay")
public class RazorpayGatewayConfig {

    static final int CONNECT_TIMEOUT_MS = 3_000;
    static final int READ_TIMEOUT_MS = 10_000;

    @Bean
    PaymentGateway razorpayGateway(PaymentGatewayProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        RestClient restClient = RestClient.builder().requestFactory(factory).build();
        return new RazorpayGateway(properties, restClient);
    }
}
