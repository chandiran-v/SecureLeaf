package com.secureleaf.commerce.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * {@code payment.gateway.*}. The three secrets mirror a Razorpay account exactly.
 *
 * @param provider      which {@link PaymentGateway} bean to create: {@code mock} or {@code razorpay}
 * @param keyId         public key id — safe to send to the browser (checkout.js {@code key}).
 *                      Its prefix says which mode the account is in: {@code rzp_test_} / {@code rzp_live_}.
 * @param keySecret     private — signs the checkout {@code razorpay_signature} and is the HTTP Basic
 *                      password for Razorpay's REST API. Never leaves the server, never logged.
 * @param webhookSecret private — signs webhook bodies ({@code X-Razorpay-Signature}). A different
 *                      secret from keySecret, so leaking one doesn't let an attacker forge the other.
 * @param baseUrl       Razorpay REST base URL; configurable so tests can point it elsewhere (D1).
 */
@ConfigurationProperties(prefix = "payment.gateway")
public record PaymentGatewayProperties(String provider, String keyId, String keySecret, String webhookSecret,
                                       String baseUrl) {

    public static final String DEFAULT_BASE_URL = "https://api.razorpay.com/v1";

    @ConstructorBinding
    public PaymentGatewayProperties {
        if (baseUrl == null || baseUrl.isBlank()) baseUrl = DEFAULT_BASE_URL;
    }

    public PaymentGatewayProperties(String provider, String keyId, String keySecret, String webhookSecret) {
        this(provider, keyId, keySecret, webhookSecret, DEFAULT_BASE_URL);
    }

    /** Never print secrets: a record's default toString would include them in any log line or stack trace. */
    @Override
    public String toString() {
        return "PaymentGatewayProperties[provider=" + provider + ", keyId=" + keyId + ", secrets=***]";
    }
}
