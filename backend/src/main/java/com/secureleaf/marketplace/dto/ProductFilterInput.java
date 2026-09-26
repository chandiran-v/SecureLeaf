package com.secureleaf.marketplace.dto;

/**
 * Mirrors the GraphQL {@code ProductFilterInput} input type.
 *
 * {@code sortBy} is a free-form string on the wire but is only ever matched against
 * a fixed whitelist of values inside {@link com.secureleaf.marketplace.repository.ProductSearchRepositoryImpl}
 * (see D1 in the phase-3 design doc) — it is never interpolated into SQL.
 */
public record ProductFilterInput(
        String categorySlug,
        Integer minPricePaise,
        Integer maxPricePaise,
        Boolean isFree,
        String searchQuery,
        String sortBy,
        Double minRating
) {}
