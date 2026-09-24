package com.secureleaf.commerce.dto;

/**
 * The three values Razorpay's checkout.js hands to its {@code handler} callback on success
 * ({@code razorpay_order_id}, {@code razorpay_payment_id}, {@code razorpay_signature}), plus
 * our own order id. All four are non-null in the GraphQL schema ({@code String!}/{@code ID!}),
 * so GraphQL itself rejects a missing field before this record is built.
 */
public record VerifyPaymentInput(
        Long orderId,
        String gatewayOrderId,
        String gatewayPaymentId,
        String gatewaySignature
) {}
