package com.secureleaf.marketplace.resolver;

import com.secureleaf.auth.service.SecureLeafUserDetails;
import com.secureleaf.marketplace.dto.RatingCountDto;
import com.secureleaf.marketplace.dto.ReviewDto;
import com.secureleaf.marketplace.dto.ReviewPageDto;
import com.secureleaf.marketplace.dto.SubmitReviewInput;
import com.secureleaf.marketplace.service.ReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;

import java.util.List;

/**
 * GraphQL resolver for reviews & ratings (REV-01..04).
 *
 * {@code productReviews} and {@code ratingBreakdown} carry no @PreAuthorize — like
 * {@code products}/{@code product} (ProductResolver), a product's rating summary and review
 * list are public marketplace data. {@code myReview}, {@code submitReview} and
 * {@code deleteMyReview} require a logged-in buyer; the entitlement/ownership checks that
 * decide WHO can actually write a review live in {@link ReviewService}, not here.
 */
@Controller
@RequiredArgsConstructor
public class ReviewResolver {

    private final ReviewService reviewService;

    // ── Queries ──────────────────────────────────────────────────────────────

    @QueryMapping
    public ReviewPageDto productReviews(@Argument Long productId, @Argument int page, @Argument int size) {
        return reviewService.productReviews(productId, page, size);
    }

    @QueryMapping
    public List<RatingCountDto> ratingBreakdown(@Argument Long productId) {
        return reviewService.ratingBreakdown(productId);
    }

    @QueryMapping
    @PreAuthorize("isAuthenticated()")
    public ReviewDto myReview(@Argument Long productId) {
        return reviewService.myReview(getCurrentUserId(), productId);
    }

    // ── Mutations ────────────────────────────────────────────────────────────

    @MutationMapping
    @PreAuthorize("isAuthenticated()")
    public ReviewDto submitReview(@Argument SubmitReviewInput input) {
        return reviewService.submitReview(getCurrentUserId(), input.productId(), input.rating(), input.reviewText());
    }

    @MutationMapping
    @PreAuthorize("isAuthenticated()")
    public boolean deleteMyReview(@Argument Long productId) {
        return reviewService.deleteMyReview(getCurrentUserId(), productId);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Long getCurrentUserId() {
        SecureLeafUserDetails userDetails = (SecureLeafUserDetails) SecurityContextHolder
                .getContext().getAuthentication().getPrincipal();
        return userDetails.getUserId();
    }
}
