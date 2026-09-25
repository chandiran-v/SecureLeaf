package com.secureleaf.viewer;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.time.Clock;

/**
 * Registers {@link DrmProperties} and refuses to start in the {@code prod} profile with the
 * dev-only signing secret (D11) — the same fail-fast pattern as {@code CommerceConfig}'s
 * payment-gateway guard.
 *
 * WHY FAIL FAST
 * {@link com.secureleaf.viewer.security.TileUrlSigner} proves a tile URL is genuine purely by
 * recomputing its HMAC with this secret. Anyone who knows the secret can mint their own
 * "signed" URL for any session, page, or user — the entire D5/D6 defense collapses. Because
 * the secret has a working default in application.yml (so `docker compose up` needs no setup),
 * it's trivially easy to forget to set {@code DRM_SIGNING_SECRET} on a real deployment. Crashing
 * at startup turns that mistake into a five-second local failure instead of a silent production
 * vulnerability.
 */
@Configuration
@EnableConfigurationProperties(DrmProperties.class)
@RequiredArgsConstructor
@Slf4j
public class DrmConfig {

    static final String DEV_DEFAULT_SECRET = "dev-only-change-me-32-bytes-minimum!!";

    private final DrmProperties drmProperties;
    private final Environment environment;

    @PostConstruct
    void checkSigningSecretIsNotDevDefaultInProd() {
        if (environment.matchesProfiles("prod") && DEV_DEFAULT_SECRET.equals(drmProperties.signingSecret())) {
            throw new IllegalStateException(
                    "drm.signing-secret is still the dev default. Refusing to start in the 'prod' profile: "
                            + "a known signing secret lets anyone mint their own tile URLs. Set DRM_SIGNING_SECRET.");
        }
    }

    /**
     * Injectable so {@code TileUrlSignerTest} can fix "now" instead of racing the real clock —
     * see TileUrlSigner's expiry/tamper tests.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
