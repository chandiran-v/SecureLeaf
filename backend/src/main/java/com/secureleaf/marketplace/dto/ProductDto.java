package com.secureleaf.marketplace.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Data Transfer Object for the GraphQL {@code Product} type.
 *
 * Why a DTO instead of returning the entity directly?
 * 1. The GraphQL schema's Product.tags is [String!]! but the entity holds List<ProductTag>.
 * 2. Product.creator and Product.category are lazy — touching them outside a transaction
 *    throws LazyInitializationException (same bug as A1 on the me query).
 * 3. DTOs decouple the API surface from the database model — adding a column to the
 *    products table doesn't automatically leak into the API response.
 *
 * {@code creator} is a {@link CreatorSummaryDto}, not the full {@code UserDto} — the
 * marketplace is public, so the creator's email must never reach this DTO in the
 * first place (see D4 in the phase-3 design doc).
 *
 * {@code thumbnailKey} holds the raw MinIO object key, not a URL. Turning it into a
 * presigned, browsable URL is done by {@link com.secureleaf.marketplace.resolver.ProductFieldResolver}
 * only when a client actually selects {@code Product.thumbnailUrl} (D3) — that keeps
 * this mapper a dependency-free static utility.
 *
 * All mapping from entity → DTO happens inside @Transactional service methods
 * so the session is still open when lazy associations are accessed.
 */
public record ProductDto(
        Long id,
        String title,
        String description,
        Long pricePaise,
        String status,
        CreatorSummaryDto creator,
        CategoryDto category,
        List<String> tags,
        String thumbnailKey,
        Double averageRating,
        Integer totalSales,
        Integer freePreviewPages,
        Integer pageCount,
        OffsetDateTime createdAt,
        String takedownReason
) {}
