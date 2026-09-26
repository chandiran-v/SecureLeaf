package com.secureleaf.marketplace.service;

import com.secureleaf.commerce.entity.EntitlementStatus;
import com.secureleaf.commerce.repository.EntitlementRepository;
import com.secureleaf.common.exception.BusinessException;
import com.secureleaf.common.exception.DuplicateResourceException;
import com.secureleaf.common.exception.ErrorCode;
import com.secureleaf.common.exception.ResourceNotFoundException;
import com.secureleaf.marketplace.dto.RatingCountDto;
import com.secureleaf.marketplace.dto.ReviewDto;
import com.secureleaf.marketplace.dto.ReviewPageDto;
import com.secureleaf.marketplace.entity.Product;
import com.secureleaf.marketplace.entity.Review;
import com.secureleaf.marketplace.mapper.ReviewMapper;
import com.secureleaf.marketplace.repository.ProductRepository;
import com.secureleaf.marketplace.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reviews & ratings (REV-01..04).
 *
 * Every write here (submit, edit-via-upsert, delete) follows the same three-step recipe from
 * D4: lock the product row, write the review, recompute the aggregate from scratch. See
 * {@link ProductRepository#findByIdForUpdate} and {@link ProductRepository#recalculateReviewAggregate}
 * for why each step exists — the short version is that the lock serialises concurrent writers
 * for the SAME product so the aggregate recompute never misses a review that committed in
 * between a naive read and a naive write (the lost-update anomaly).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReviewService {

    private static final int MAX_REVIEW_TEXT_LENGTH = 2_000;
    private static final int MAX_PAGE_SIZE = 50;

    private final ReviewRepository reviewRepository;
    private final ProductRepository productRepository;
    private final EntitlementRepository entitlementRepository;

    // ── Submit / edit (upsert, D2) ──────────────────────────────────────────

    @Transactional
    public ReviewDto submitReview(Long buyerId, Long productId, Integer rating, String reviewText) {
        String cleanedText = validateInput(rating, reviewText);

        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product", productId));

        // D1 — the creator can never review their own product, regardless of entitlement.
        if (product.getCreator().getId().equals(buyerId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "You cannot review your own product.");
        }
        var entitlement = entitlementRepository.findByBuyerIdAndProductIdAndStatus(buyerId, productId, EntitlementStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_ENTITLED, "You must own this product to review it."));

        // D4 step 1 — serialise every writer for THIS product before touching reviews/aggregate.
        productRepository.findByIdForUpdate(productId);

        Review review = reviewRepository.findByBuyerIdAndProductId(buyerId, productId)
                .orElseGet(() -> {
                    Review created = new Review();
                    created.setProduct(product);
                    created.setBuyer(entitlement.getBuyer());
                    return created;
                });
        review.setRating(rating.shortValue());
        review.setReviewText(cleanedText);

        try {
            // D4 step 2 — the review write, inside the same lock held since step 1.
            review = reviewRepository.save(review);
        } catch (DataIntegrityViolationException e) {
            // D2's final backstop: uq_reviews_buyer_product. In practice the row lock above
            // already serialises every writer for this product, so a genuine race here would
            // require two requests from the SAME buyer racing each other past the upsert
            // check — unlikely, but the unique constraint guarantees it's never silently
            // double-inserted; it's reported instead of failing invisibly.
            throw new DuplicateResourceException("Review", "buyer/product", buyerId + "/" + productId);
        }

        // D4 step 3 — recompute the aggregate from scratch, still inside the same lock.
        productRepository.recalculateReviewAggregate(productId);

        log.info("Review upserted: buyerId={}, productId={}, rating={}", buyerId, productId, rating);
        return ReviewMapper.toDto(review);
    }

    // ── Delete ───────────────────────────────────────────────────────────────

    @Transactional
    public boolean deleteMyReview(Long buyerId, Long productId) {
        Optional<Review> existing = reviewRepository.findByBuyerIdAndProductId(buyerId, productId);
        if (existing.isEmpty()) {
            return false;
        }

        // D4 step 1 — same lock as submitReview, so a concurrent submit/delete on this
        // product can't interleave with this delete's aggregate recompute.
        productRepository.findByIdForUpdate(productId);

        reviewRepository.delete(existing.get());
        productRepository.recalculateReviewAggregate(productId);

        log.info("Review deleted: buyerId={}, productId={}", buyerId, productId);
        return true;
    }

    // ── Reads ────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ReviewPageDto productReviews(Long productId, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        return ReviewMapper.toPageDto(reviewRepository.findByProductIdOrderByCreatedAtDesc(productId, pageable));
    }

    @Transactional(readOnly = true)
    public ReviewDto myReview(Long buyerId, Long productId) {
        return reviewRepository.findByBuyerIdAndProductId(buyerId, productId).map(ReviewMapper::toDto).orElse(null);
    }

    /** D6 — always all five star values, zero-filled, highest first — a stable histogram shape. */
    @Transactional(readOnly = true)
    public List<RatingCountDto> ratingBreakdown(Long productId) {
        Map<Integer, Long> counts = new LinkedHashMap<>();
        for (int star = 5; star >= 1; star--) {
            counts.put(star, 0L);
        }
        for (Object[] row : reviewRepository.countByRatingForProduct(productId)) {
            int rating = ((Number) row[0]).intValue();
            long count = ((Number) row[1]).longValue();
            counts.put(rating, count);
        }
        return counts.entrySet().stream()
                .map(e -> new RatingCountDto(e.getKey(), e.getValue()))
                .toList();
    }

    // ── Validation (D3) ─────────────────────────────────────────────────────

    private String validateInput(Integer rating, String reviewText) {
        if (rating == null || rating < 1 || rating > 5) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "Rating must be between 1 and 5.");
        }
        if (reviewText == null) {
            return null;
        }
        String trimmed = reviewText.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_REVIEW_TEXT_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "Review text cannot exceed " + MAX_REVIEW_TEXT_LENGTH + " characters.");
        }
        return trimmed;
    }
}
