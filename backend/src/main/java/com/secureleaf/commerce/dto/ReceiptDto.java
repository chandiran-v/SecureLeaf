package com.secureleaf.commerce.dto;

import java.time.OffsetDateTime;

/** GraphQL {@code OrderReceipt} (Phase 09C D5) — everything a printable receipt shows. */
public record ReceiptDto(
        Long orderId,
        String status,
        OffsetDateTime purchasedAt,
        String productTitle,
        String creatorName,
        Long amountPaise,
        String paymentIdMasked,
        String paymentMode
) {}
