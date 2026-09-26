package com.secureleaf.marketplace.dto;

import java.util.List;

/** Mirrors the GraphQL {@code ReviewPage} type (D6) — newest reviews first. */
public record ReviewPageDto(
        List<ReviewDto> content,
        int totalElements,
        int totalPages,
        int pageNumber
) {}
