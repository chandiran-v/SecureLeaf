package com.secureleaf.creator.dto;

import java.time.OffsetDateTime;

/**
 * One row of a creator statement (Phase 09C D4). Amounts are SIGNED paise from the creator's point
 * of view: a sale is positive, a refund or payout negative. {@code type} is SALE, REFUND or PAYOUT.
 */
public record StatementLineDto(OffsetDateTime date, String type, String description,
                               long grossPaise, long feePaise, long netPaise) {}
