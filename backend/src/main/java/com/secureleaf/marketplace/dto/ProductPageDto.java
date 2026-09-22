package com.secureleaf.marketplace.dto;

import java.util.List;

/**
 * Data Transfer Object for the GraphQL {@code ProductPage} type — offset-based
 * pagination envelope (see D6: schema is offset-based, so the frontend uses
 * numbered pagination rather than fetchMore/relay-style cursors).
 */
public record ProductPageDto(
        List<ProductDto> content,
        int totalElements,
        int totalPages,
        int pageNumber
) {}
