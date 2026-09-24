package com.secureleaf.commerce.dto;

import com.secureleaf.marketplace.dto.ProductDto;

import java.time.OffsetDateTime;

/** GraphQL {@code Entitlement} — one row of the buyer's library. */
public record EntitlementDto(
        Long id,
        ProductDto product,
        OffsetDateTime purchasedAt,
        String status
) {}
