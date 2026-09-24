package com.secureleaf.commerce.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code payment.gateway.*}. The three secrets mirror a Razorpay account exactly.
 *
 * @param provider      which {@link PaymentGateway} bean to create: {@code mock} or (later) {@code razorpay}
 * @param keyId         public key id — safe to send to the browser (checkout.js {@code key})
 * @param keySecret     private — signs the checkout {@code razorpay_signature}. Never leaves the server.
 * @param webhookSecret private — signs webhook bodies ({@code X-Razorpay-Signature}). A different
 *                      secret from keySecret, so leaking one doesn't let an attacker forge the other.
 */
@ConfigurationProperties(prefix = "payment.gateway")
public record PaymentGatewayProperties(String provider, String keyId, String keySecret, String webhookSecret) {
}
