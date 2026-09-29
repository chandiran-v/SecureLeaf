package com.secureleaf.commerce.gateway;

/** A refund as the gateway reports it (D2/D6). {@code status}: pending | processed | failed. */
public record GatewayRefund(String id, String paymentId, long amountPaise, String status) {}
