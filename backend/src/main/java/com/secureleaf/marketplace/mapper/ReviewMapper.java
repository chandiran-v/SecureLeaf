package com.secureleaf.marketplace.mapper;

import com.secureleaf.marketplace.dto.ReviewDto;
import com.secureleaf.marketplace.dto.ReviewerSummaryDto;
import com.secureleaf.marketplace.entity.Review;
import org.springframework.data.domain.Page;

import java.time.ZoneOffset;

/**
 * Static mapping utility between {@link Review} and {@link ReviewDto} — same "must be called
 * inside a transaction" contract as {@link ProductMapper} (Review.buyer is a LAZY association).
 */
public class ReviewMapper {

    private ReviewMapper() {} // utility class — no instantiation

    public static ReviewDto toDto(Review review) {
        if (review == null) return null;
        return new ReviewDto(
                review.getId(),
                new ReviewerSummaryDto(review.getBuyer().getDisplayName()),
                review.getRating().intValue(),
                review.getReviewText(),
                review.getCreatedAt() != null ? review.getCreatedAt().atOffset(ZoneOffset.UTC) : null,
                review.getUpdatedAt() != null ? review.getUpdatedAt().atOffset(ZoneOffset.UTC) : null
        );
    }

    public static com.secureleaf.marketplace.dto.ReviewPageDto toPageDto(Page<Review> page) {
        return new com.secureleaf.marketplace.dto.ReviewPageDto(
                page.getContent().stream().map(ReviewMapper::toDto).toList(),
                Math.toIntExact(page.getTotalElements()),
                page.getTotalPages(),
                page.getNumber()
        );
    }
}
