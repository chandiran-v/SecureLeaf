package com.secureleaf.commerce.dto;

import com.secureleaf.marketplace.dto.ProductDto;

import java.time.OffsetDateTime;

/** GraphQL {@code Order}. MVP orders hold exactly one product, exposed directly as {@code product}. */
public record OrderDto(
        Long id,
        String status,
        Long totalAmountPaise,
        ProductDto product,
        String gatewayOrderId,
        String failureReason,
        OffsetDateTime createdAt
) {}
