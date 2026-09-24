package com.secureleaf.commerce.dto;

/**
 * What the browser needs to open the gateway's checkout — exactly the fields Razorpay's
 * checkout.js takes ({@code key}, {@code order_id}, {@code amount}, {@code currency}).
 * {@code gatewayOrderId}/{@code gatewayKeyId} are null for free products, whose order
 * is already COMPLETED (D10).
 */
public record InitiateOrderPayload(
        OrderDto order,
        String gatewayOrderId,
        String gatewayKeyId,
        String currency
) {}
