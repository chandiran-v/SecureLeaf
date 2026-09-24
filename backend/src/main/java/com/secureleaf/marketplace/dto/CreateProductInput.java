package com.secureleaf.marketplace.dto;

import java.util.List;

/**
 * Mirrors the GraphQL {@code CreateProductInput} input type.
 * Spring for GraphQL automatically deserializes the @Argument into this record.
 */
public record CreateProductInput(
        String title,
        String description,
        Integer pricePaise,
        Long categoryId,
        List<String> tags,
        Integer freePreviewPages
) {}
