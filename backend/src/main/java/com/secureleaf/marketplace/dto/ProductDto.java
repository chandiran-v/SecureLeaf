package com.secureleaf.marketplace.dto;

import com.secureleaf.auth.dto.UserDto;

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
 * All mapping from entity → DTO happens inside @Transactional service methods
 * so the session is still open when lazy associations are accessed.
 */
public record ProductDto(
        Long id,
        String title,
        String description,
        Long pricePaise,
        String status,
        UserDto creator,
        CategoryDto category,
        List<String> tags,
        String thumbnailUrl,
        Double averageRating,
        Integer totalSales,
        Integer freePreviewPages,
        OffsetDateTime createdAt
) {}
