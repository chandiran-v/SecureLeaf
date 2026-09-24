package com.secureleaf.commerce;

import com.secureleaf.commerce.gateway.PaymentGatewayProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

/**
 * Registers the commerce property records and refuses to start with a gateway that
 * doesn't exist (D12).
 *
 * WHY FAIL FAST
 * application-prod.yml defaults {@code payment.gateway.provider} to {@code razorpay}. Until a
 * real RazorpayGateway is written, the only implementation is the mock — whose checkout
 * endpoint marks any order as paid for free. Silently falling back to it in production would
 * be a "free purchases for everyone" bug. Crashing at startup with a clear message is the
 * safe failure: nobody can deploy that mistake.
 */
@Configuration
@EnableConfigurationProperties({CommerceProperties.class, PaymentGatewayProperties.class})
@RequiredArgsConstructor
@Slf4j
public class CommerceConfig {

    private static final Set<String> IMPLEMENTED_PROVIDERS = Set.of("mock");

    private final PaymentGatewayProperties gatewayProperties;

    @PostConstruct
    void checkGatewayIsImplemented() {
        String provider = gatewayProperties.provider();
        if (!IMPLEMENTED_PROVIDERS.contains(provider)) {
            throw new IllegalStateException("payment.gateway.provider='" + provider
                    + "' has no implementation yet (available: " + IMPLEMENTED_PROVIDERS
                    + "). Refusing to start rather than fall back to the mock gateway.");
        }
        if ("mock".equals(provider)) {
            log.warn("Payment gateway is MOCK — every checkout can be marked paid without real money. "
                    + "Never run this in production.");
        }
    }
}
