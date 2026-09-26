package com.secureleaf.marketplace.dto;

/**
 * Public-safe projection of a review's author — mirrors the GraphQL {@code ReviewerSummary}
 * type (D5). Deliberately carries only a display name, not even the buyer's id: the original
 * {@code Review.buyer: User!} field exposed {@code User.email} to anyone who could see the
 * review, which is the privacy leak this phase fixes. See {@code docs/learning-notes/phase-07-*}
 * for the "data minimisation" writeup.
 */
public record ReviewerSummaryDto(String displayName) {}
