package com.secureleaf.content.dto;

import java.time.OffsetDateTime;

/**
 * One row of the creator's version history (Phase 15, D7).
 *
 * {@code status}: PROCESSING (queued or running), READY, FAILED or RETIRED. {@code buyerCount} is
 * the number of ACTIVE entitlements currently pinned to this version.
 */
public record DocumentVersionDto(
        Long id,
        Integer versionNumber,
        OffsetDateTime createdAt,
        Integer pageCount,
        String status,
        String updatePolicy,
        Integer buyerCount,
        Boolean current,
        String failureReason,
        Boolean migrationPending
) {}
