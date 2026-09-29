package com.secureleaf.common.config;

import com.secureleaf.auth.config.JwtProperties;
import com.secureleaf.commerce.gateway.PaymentGatewayProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Phase 9, D6 — refuses to start in the {@code prod} profile if any of these secrets still has
 * its dev-only default value. Same fail-fast pattern as {@link com.secureleaf.viewer.DrmConfig}
 * (which already does this for {@code drm.signing-secret}) and
 * {@link com.secureleaf.commerce.CommerceConfig} (which already refuses an unimplemented payment
 * provider) — this class covers the rest of D6's list: the JWT signing secret, the two Razorpay
 * secrets, and the MinIO credentials.
 *
 * WHY THIS MATTERS MORE THAN A NORMAL CONFIG MISTAKE
 * Every one of these has a working default in {@code application.yml} specifically so
 * {@code docker compose up} needs zero setup for local development. That convenience is exactly
 * what makes it easy to forget to override one in a real deployment — and each of these secrets,
 * left at its published, world-readable default, is a full authentication bypass:
 *   - jwt.secret: anyone can mint their own "valid" access token for any user id.
 *   - payment.gateway.key-secret / webhook-secret: anyone can forge a "verified" checkout
 *     signature or a fake webhook, granting themselves free entitlements.
 *   - minio.access-key / secret-key: anyone who finds the (also default) MinIO console can read
 *     every buyer's watermarked tiles and every creator's raw, unwatermarked PDF.
 * A crash at startup, with a message naming exactly which variable is missing, turns each of
 * these from a silent production vulnerability into a five-second local failure the deployer
 * cannot miss.
 */
@Configuration
@RequiredArgsConstructor
public class ProdSecretsConfig {

    static final String DEV_JWT_SECRET = "CHANGE_ME_IN_PRODUCTION_USE_256BIT_SECRET_KEY";
    static final String DEV_RAZORPAY_KEY_SECRET = "mock_key_secret_change_me";
    static final String DEV_RAZORPAY_WEBHOOK_SECRET = "mock_webhook_secret_change_me";
    static final String DEV_RAZORPAY_KEY_ID = "rzp_test_mock_key";
    static final String DEV_MINIO_ACCESS_KEY = "secureleaf_minio_user";
    static final String DEV_MINIO_SECRET_KEY = "secureleaf_minio_pass";

    // Phase 09D D10 — the infra secrets the prod compose file generates. Read from the Environment
    // (not injected properties) because only these checks need them.
    static final String DEV_DB_PASSWORD = "secureleaf_pass";
    static final String DEV_REDIS_PASSWORD = "secureleaf_redis_pass";
    static final String DEV_DRM_SIGNING_SECRET = "dev-only-change-me-32-bytes-minimum!!";
    /** 256 bits = 32 bytes; a shorter HMAC key weakens the signature. */
    static final int MIN_SECRET_CHARS = 32;

    private final JwtProperties jwtProperties;
    private final PaymentGatewayProperties paymentGatewayProperties;
    private final MinioProperties minioProperties;
    private final Environment environment;

    @PostConstruct
    void checkNoDevDefaultsInProd() {
        if (!environment.matchesProfiles("prod")) {
            return;
        }

        Map<String, String> devDefaultsStillInUse = new LinkedHashMap<>();
        putIfDevDefault(devDefaultsStillInUse, "JWT_SECRET", jwtProperties.getSecret(), DEV_JWT_SECRET);
        putIfDevDefault(devDefaultsStillInUse, "RAZORPAY_KEY_SECRET", paymentGatewayProperties.keySecret(), DEV_RAZORPAY_KEY_SECRET);
        putIfDevDefault(devDefaultsStillInUse, "RAZORPAY_WEBHOOK_SECRET", paymentGatewayProperties.webhookSecret(), DEV_RAZORPAY_WEBHOOK_SECRET);
        putIfDevDefault(devDefaultsStillInUse, "MINIO_ACCESS_KEY", minioProperties.getAccessKey(), DEV_MINIO_ACCESS_KEY);
        putIfDevDefault(devDefaultsStillInUse, "MINIO_SECRET_KEY", minioProperties.getSecretKey(), DEV_MINIO_SECRET_KEY);
        putIfDevDefault(devDefaultsStillInUse, "DB_PASSWORD", environment.getProperty("spring.datasource.password"), DEV_DB_PASSWORD);
        putIfDevDefault(devDefaultsStillInUse, "REDIS_PASSWORD", environment.getProperty("spring.data.redis.password"), DEV_REDIS_PASSWORD);

        // Phase 09D D10 — an empty value ("JWT_SECRET=" in a half-filled .env) or a too-short one is
        // just as unsafe as a published default. Values the caller never configured (null) are left
        // to the checks above/the owning config class.
        Map<String, String> weak = new LinkedHashMap<>();
        putIfWeakSecret(weak, "JWT_SECRET", jwtProperties.getSecret());
        putIfWeakSecret(weak, "DRM_SIGNING_SECRET", environment.getProperty("drm.signing-secret"));
        putIfBlankButSet(weak, "DB_PASSWORD", environment.getProperty("spring.datasource.password"));
        putIfBlankButSet(weak, "REDIS_PASSWORD", environment.getProperty("spring.data.redis.password"));
        putIfBlankButSet(weak, "MINIO_ACCESS_KEY", minioProperties.getAccessKey());
        putIfBlankButSet(weak, "MINIO_SECRET_KEY", minioProperties.getSecretKey());
        if (!weak.isEmpty()) {
            throw new IllegalStateException(
                    "Refusing to start in the 'prod' profile: " + weak.keySet()
                            + " must be set (non-empty; JWT_SECRET and DRM_SIGNING_SECRET at least "
                            + MIN_SECRET_CHARS + " characters). Run infra/prod/scripts/generate-env.sh.");
        }

        // Phase 09B D10 — with the real gateway, a blank credential would boot fine and then fail on
        // the first checkout. Catch it at startup instead.
        Map<String, String> blankRazorpay = new LinkedHashMap<>();
        if ("razorpay".equals(paymentGatewayProperties.provider())) {
            putIfBlank(blankRazorpay, "RAZORPAY_KEY_ID", paymentGatewayProperties.keyId());
            putIfBlank(blankRazorpay, "RAZORPAY_KEY_SECRET", paymentGatewayProperties.keySecret());
            putIfBlank(blankRazorpay, "RAZORPAY_WEBHOOK_SECRET", paymentGatewayProperties.webhookSecret());
            if (DEV_RAZORPAY_KEY_ID.equals(paymentGatewayProperties.keyId())) {
                blankRazorpay.put("RAZORPAY_KEY_ID", DEV_RAZORPAY_KEY_ID);
            }
        }
        if (!blankRazorpay.isEmpty()) {
            throw new IllegalStateException(
                    "Refusing to start in the 'prod' profile with payment.gateway.provider=razorpay: "
                            + blankRazorpay.keySet() + " must be set to real Razorpay credentials "
                            + "(blank, or the mock placeholder key id).");
        }

        if (!devDefaultsStillInUse.isEmpty()) {
            throw new IllegalStateException(
                    "Refusing to start in the 'prod' profile: the following environment variable(s) "
                            + "still have their dev-only default value, which is publicly known (it's in "
                            + "this repo's application.yml): " + devDefaultsStillInUse.keySet()
                            + ". Set a real, unique value for each before deploying.");
        }
    }

    private static void putIfBlankButSet(Map<String, String> violations, String envVarName, String actualValue) {
        if (actualValue != null && actualValue.isBlank()) {
            violations.put(envVarName, "");
        }
    }

    private static void putIfWeakSecret(Map<String, String> violations, String envVarName, String actualValue) {
        if (actualValue != null && actualValue.length() < MIN_SECRET_CHARS) {
            violations.put(envVarName, "");
        }
    }

    private static void putIfBlank(Map<String, String> violations, String envVarName, String actualValue) {
        if (actualValue == null || actualValue.isBlank()) {
            violations.put(envVarName, "");
        }
    }

    private static void putIfDevDefault(Map<String, String> violations, String envVarName, String actualValue, String devDefault) {
        if (devDefault.equals(actualValue)) {
            violations.put(envVarName, devDefault);
        }
    }
}
