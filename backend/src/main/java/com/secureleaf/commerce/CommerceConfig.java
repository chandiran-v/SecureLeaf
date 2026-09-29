package com.secureleaf.commerce;

import com.secureleaf.commerce.gateway.PaymentGatewayProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.Set;

/**
 * Registers the commerce property records and refuses to start with an unsafe payment set-up
 * (Phase 4 D12, Phase 09B D8).
 *
 * WHY FAIL FAST
 * Three mistakes here cost real money or hand out free purchases, and all three are one wrong
 * environment variable away:
 *   1. an unknown provider, silently falling back to the mock;
 *   2. the mock in production — its checkout endpoint marks any order as paid for free;
 *   3. a LIVE Razorpay key ({@code rzp_live_…}) before the business is ready to take real money
 *      (Phase 18 does KYC and flips {@code payment.live-enabled}).
 * Crashing at startup with a clear message is the safe failure: nobody can deploy that mistake.
 */
@Configuration
@EnableConfigurationProperties({CommerceProperties.class, PaymentGatewayProperties.class, PaymentProperties.class})
@RequiredArgsConstructor
@Slf4j
public class CommerceConfig {

    static final Set<String> IMPLEMENTED_PROVIDERS = Set.of("mock", "razorpay");
    static final String LIVE_KEY_PREFIX = "rzp_live_";

    private final PaymentGatewayProperties gatewayProperties;
    private final PaymentProperties paymentProperties;
    private final Environment environment;

    @PostConstruct
    void checkPaymentSetupIsSafe() {
        String provider = gatewayProperties.provider();
        if (!IMPLEMENTED_PROVIDERS.contains(provider)) {
            throw new IllegalStateException("payment.gateway.provider='" + provider
                    + "' has no implementation (available: " + IMPLEMENTED_PROVIDERS
                    + "). Refusing to start rather than fall back to the mock gateway.");
        }
        if ("mock".equals(provider)) {
            if (environment.matchesProfiles("prod")) {
                throw new IllegalStateException("payment.gateway.provider=mock is forbidden in the 'prod' profile: "
                        + "the mock checkout marks any order as paid without money. Set PAYMENT_GATEWAY=razorpay.");
            }
            log.warn("Payment gateway is MOCK — every checkout can be marked paid without real money. "
                    + "Never run this in production.");
        }

        String keyId = gatewayProperties.keyId();
        if (keyId != null && keyId.startsWith(LIVE_KEY_PREFIX) && !paymentProperties.liveEnabled()) {
            // Deliberately does not print the key itself, only its prefix.
            throw new IllegalStateException("Refusing to start: RAZORPAY_KEY_ID is a LIVE key (" + LIVE_KEY_PREFIX
                    + "…) but payment.live-enabled is not true. Live payments are switched on in Phase 18; "
                    + "until then use a rzp_test_ key.");
        }
        if ("razorpay".equals(provider)) {
            log.info("Payment gateway is RAZORPAY in {} mode (live-enabled={})",
                    keyId != null && keyId.startsWith(LIVE_KEY_PREFIX) ? "LIVE" : "TEST", paymentProperties.liveEnabled());
        }
    }
}
