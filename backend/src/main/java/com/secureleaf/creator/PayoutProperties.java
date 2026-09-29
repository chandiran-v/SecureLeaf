package com.secureleaf.creator;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code payouts.*} settings (Phase 09C).
 *
 * @param holdDays        days a sale's earnings stay "pending" before they can be paid out (D1); matches the
 *                        7-day refund window so money that may still be refunded is never paid out
 * @param minAmountPaise  smallest payout a creator may request (D2); 10000 = ₹100
 */
@ConfigurationProperties(prefix = "payouts")
public record PayoutProperties(int holdDays, long minAmountPaise) {}
