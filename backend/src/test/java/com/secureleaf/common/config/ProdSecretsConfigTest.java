package com.secureleaf.common.config;

import com.secureleaf.auth.config.JwtProperties;
import com.secureleaf.commerce.gateway.PaymentGatewayProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 9, D6 — plain unit test (no Spring context needed; {@code @PostConstruct} is just a
 * regular method here) proving the validator rejects dev defaults in {@code prod} and accepts
 * real values, and never interferes with non-prod profiles at all.
 */
class ProdSecretsConfigTest {

    private static JwtProperties jwtWith(String secret) {
        JwtProperties props = new JwtProperties();
        props.setSecret(secret);
        return props;
    }

    private static MinioProperties minioWith(String accessKey, String secretKey) {
        MinioProperties props = new MinioProperties();
        props.setEndpoint("http://localhost:9000");
        props.setAccessKey(accessKey);
        props.setSecretKey(secretKey);
        return props;
    }

    private static PaymentGatewayProperties paymentWith(String keySecret, String webhookSecret) {
        return new PaymentGatewayProperties("mock", "rzp_test_mock_key", keySecret, webhookSecret);
    }

    @Test
    void prodProfile_withEveryDevDefaultStillSet_throwsNamingAllOfThem() {
        MockEnvironment prod = new MockEnvironment();
        prod.addActiveProfile("prod");
        ProdSecretsConfig config = new ProdSecretsConfig(
                jwtWith(ProdSecretsConfig.DEV_JWT_SECRET),
                paymentWith(ProdSecretsConfig.DEV_RAZORPAY_KEY_SECRET, ProdSecretsConfig.DEV_RAZORPAY_WEBHOOK_SECRET),
                minioWith(ProdSecretsConfig.DEV_MINIO_ACCESS_KEY, ProdSecretsConfig.DEV_MINIO_SECRET_KEY),
                prod);

        assertThatThrownBy(config::checkNoDevDefaultsInProd)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET")
                .hasMessageContaining("RAZORPAY_KEY_SECRET")
                .hasMessageContaining("RAZORPAY_WEBHOOK_SECRET")
                .hasMessageContaining("MINIO_ACCESS_KEY")
                .hasMessageContaining("MINIO_SECRET_KEY");
    }

    @Test
    void prodProfile_withRealValues_startsCleanly() {
        MockEnvironment prod = new MockEnvironment();
        prod.addActiveProfile("prod");
        ProdSecretsConfig config = new ProdSecretsConfig(
                jwtWith("a-real-256-bit-secret-generated-for-this-deployment-only"),
                paymentWith("real_razorpay_key_secret", "real_razorpay_webhook_secret"),
                minioWith("real-access-key", "real-secret-key"),
                prod);

        config.checkNoDevDefaultsInProd(); // must not throw
    }

    @Test
    void prodProfile_withOneDevDefaultRemaining_namesOnlyThatOne() {
        MockEnvironment prod = new MockEnvironment();
        prod.addActiveProfile("prod");
        ProdSecretsConfig config = new ProdSecretsConfig(
                jwtWith(ProdSecretsConfig.DEV_JWT_SECRET),
                paymentWith("real_razorpay_key_secret", "real_razorpay_webhook_secret"),
                minioWith("real-access-key", "real-secret-key"),
                prod);

        assertThatThrownBy(config::checkNoDevDefaultsInProd)
                .hasMessageContaining("JWT_SECRET")
                .satisfies(ex -> assertThat(ex.getMessage())
                        .doesNotContain("RAZORPAY_KEY_SECRET")
                        .doesNotContain("MINIO_ACCESS_KEY"));
    }

    @Test
    void nonProdProfile_withDevDefaults_neverThrows() {
        MockEnvironment dev = new MockEnvironment();
        dev.addActiveProfile("dev");
        ProdSecretsConfig config = new ProdSecretsConfig(
                jwtWith(ProdSecretsConfig.DEV_JWT_SECRET),
                paymentWith(ProdSecretsConfig.DEV_RAZORPAY_KEY_SECRET, ProdSecretsConfig.DEV_RAZORPAY_WEBHOOK_SECRET),
                minioWith(ProdSecretsConfig.DEV_MINIO_ACCESS_KEY, ProdSecretsConfig.DEV_MINIO_SECRET_KEY),
                dev);

        config.checkNoDevDefaultsInProd(); // must not throw
    }

    @Test
    void prodProfile_razorpayProvider_withBlankCredentials_throwsNamingThem() {
        MockEnvironment prod = new MockEnvironment();
        prod.addActiveProfile("prod");
        ProdSecretsConfig config = new ProdSecretsConfig(
                jwtWith("a-real-256-bit-secret-generated-for-this-deployment-only"),
                new PaymentGatewayProperties("razorpay", "", "", " "),
                minioWith("real-access-key", "real-secret-key"),
                prod);

        assertThatThrownBy(config::checkNoDevDefaultsInProd)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RAZORPAY_KEY_ID")
                .hasMessageContaining("RAZORPAY_KEY_SECRET")
                .hasMessageContaining("RAZORPAY_WEBHOOK_SECRET");
    }

    @Test
    void prodProfile_razorpayProvider_withMockPlaceholderKeyId_throws() {
        MockEnvironment prod = new MockEnvironment();
        prod.addActiveProfile("prod");
        ProdSecretsConfig config = new ProdSecretsConfig(
                jwtWith("a-real-256-bit-secret-generated-for-this-deployment-only"),
                new PaymentGatewayProperties("razorpay", ProdSecretsConfig.DEV_RAZORPAY_KEY_ID, "real_secret", "real_whsec"),
                minioWith("real-access-key", "real-secret-key"),
                prod);

        assertThatThrownBy(config::checkNoDevDefaultsInProd).hasMessageContaining("RAZORPAY_KEY_ID");
    }

    @Test
    void prodProfile_razorpayProvider_withRealCredentials_startsCleanly() {
        MockEnvironment prod = new MockEnvironment();
        prod.addActiveProfile("prod");
        ProdSecretsConfig config = new ProdSecretsConfig(
                jwtWith("a-real-256-bit-secret-generated-for-this-deployment-only"),
                new PaymentGatewayProperties("razorpay", "rzp_test_realkey", "real_secret", "real_whsec"),
                minioWith("real-access-key", "real-secret-key"),
                prod);

        config.checkNoDevDefaultsInProd(); // must not throw
    }
}
