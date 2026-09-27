package com.secureleaf.admin.dto;

import com.secureleaf.marketplace.dto.CreatorSummaryDto;

import java.time.OffsetDateTime;

/** D5 — the GraphQL {@code AdminProduct} type. */
public record AdminProductDto(
        Long id,
        String title,
        String status,
        CreatorSummaryDto creator,
        Long pricePaise,
        Integer totalSales,
        OffsetDateTime takenDownAt,
        String takedownReason,
        OffsetDateTime createdAt
) {}
