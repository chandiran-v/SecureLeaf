package com.secureleaf.creator.dto;

import java.time.OffsetDateTime;

/** GraphQL {@code Payout} (Phase 09C). */
public record PayoutDto(
        Long id,
        Long creatorId,
        String creatorName,
        String creatorEmail,
        Long amountPaise,
        String status,
        Long grossRevenuePaise,
        Long platformFeePaise,
        Long netPayoutPaise,
        String payoutMethod,
        String payoutDestination,
        String payoutReference,
        OffsetDateTime requestedAt,
        OffsetDateTime processedAt,
        String notes
) {}
