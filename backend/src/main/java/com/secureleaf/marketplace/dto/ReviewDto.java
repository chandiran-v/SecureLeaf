package com.secureleaf.marketplace.dto;

import java.time.OffsetDateTime;

/**
 * Data Transfer Object for the GraphQL {@code Review} type. See {@link ReviewerSummaryDto}
 * for why this carries a reviewer summary, not the full buyer.
 */
public record ReviewDto(
        Long id,
        ReviewerSummaryDto reviewer,
        Integer rating,
        String reviewText,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
