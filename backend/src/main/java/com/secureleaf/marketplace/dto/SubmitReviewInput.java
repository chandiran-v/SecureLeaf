package com.secureleaf.marketplace.dto;

/**
 * Mirrors the GraphQL {@code SubmitReviewInput} input type.
 * Validation (rating range, reviewText length) happens in {@link com.secureleaf.marketplace.service.ReviewService},
 * not with Bean Validation annotations here — same manual-validation style as
 * {@code ProductService.validateProductInput}.
 */
public record SubmitReviewInput(Long productId, Integer rating, String reviewText) {}
