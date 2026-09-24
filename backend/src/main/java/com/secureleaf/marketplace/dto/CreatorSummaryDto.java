package com.secureleaf.marketplace.dto;

/**
 * Public-safe projection of a product's creator for the marketplace.
 *
 * Deliberately excludes email and every other User field. The full {@code UserDto}
 * (which includes email) is fine for "me" and the creator dashboard — both are
 * scoped to the authenticated user themselves — but {@code Product.creator} is
 * served to anonymous visitors, so it must not be able to leak a PII field just
 * because someone adds one to UserDto later. A separate, narrower DTO makes that
 * impossible at compile time rather than relying on resolver discipline.
 */
public record CreatorSummaryDto(Long id, String displayName) {}
