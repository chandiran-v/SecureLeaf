package com.secureleaf.admin.dto;

import com.secureleaf.marketplace.entity.ProductStatus;

/** Mirrors the GraphQL {@code AdminProductFilter} input type. {@code search} matches title,
 *  case-insensitive (D5). */
public record AdminProductFilterDto(String search, ProductStatus status, Long creatorId) {}
