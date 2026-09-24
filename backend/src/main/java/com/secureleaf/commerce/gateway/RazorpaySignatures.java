package com.secureleaf.commerce.gateway;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Razorpay's two signature schemes, implemented exactly as Razorpay documents them, so the
 * mock and the real gateway share this code (D1, D4).
 *
 * WHY AN HMAC AND NOT A PLAIN HASH
 * A hash (SHA-256 of the body) proves the body wasn't corrupted — but anyone can compute
 * it, so it proves nothing about *who* sent it. An HMAC mixes in a secret only Razorpay
 * and our server know: a valid HMAC means "whoever made this knew the secret". That is
 * what stops an attacker from POSTing a fake "payment.captured" to our public webhook URL.
 */
public final class RazorpaySignatures {

    private static final String ALGORITHM = "HmacSHA256";

    private RazorpaySignatures() {}

    /** Checkout signature: HMAC_SHA256(order_id + "|" + payment_id, key_secret), hex-encoded. */
    public static String checkoutSignature(String gatewayOrderId, String gatewayPaymentId, String keySecret) {
        return hmacHex(gatewayOrderId + "|" + gatewayPaymentId, keySecret);
    }

    /**
     * Webhook signature: HMAC_SHA256(raw request body, webhook_secret), hex-encoded.
     * Takes the body's exact BYTES — parse the JSON and re-serialise it, and whitespace or key
     * order may change, so a perfectly genuine webhook would fail verification.
     */
    public static String webhookSignature(byte[] rawBody, String webhookSecret) {
        return hmacHex(rawBody, webhookSecret);
    }

    public static String webhookSignature(String rawBody, String webhookSecret) {
        return webhookSignature(rawBody.getBytes(StandardCharsets.UTF_8), webhookSecret);
    }

    /**
     * Constant-time comparison. {@code String.equals} returns at the first differing
     * character, so how long it takes leaks how many leading characters were right; an
     * attacker who can measure that could guess a valid signature one character at a time
     * (a timing attack). {@link MessageDigest#isEqual} always compares every byte.
     */
    public static boolean matches(String expected, String provided) {
        if (expected == null || provided == null) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }

    private static String hmacHex(String payload, String secret) {
        return hmacHex(payload.getBytes(StandardCharsets.UTF_8), secret);
    }

    private static String hmacHex(byte[] payload, String secret) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(payload));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
