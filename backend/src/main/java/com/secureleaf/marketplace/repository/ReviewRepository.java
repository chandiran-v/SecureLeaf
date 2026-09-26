package com.secureleaf.marketplace.repository;

import com.secureleaf.marketplace.entity.Review;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    /** D2 — the upsert's "does this buyer already have a review for this product?" check. */
    @EntityGraph(attributePaths = "buyer")
    Optional<Review> findByBuyerIdAndProductId(Long buyerId, Long productId);

    /**
     * D6 — productReviews, newest first. No collection fetch on Review (only the
     * ManyToOne `buyer`), so pagination and @EntityGraph combine safely here — unlike
     * Product's tags, this never triggers Hibernate's in-memory-pagination fallback
     * (see ProductSearchRepositoryImpl's javadoc for that trap).
     */
    @EntityGraph(attributePaths = "buyer")
    Page<Review> findByProductIdOrderByCreatedAtDesc(Long productId, Pageable pageable);

    /**
     * D6 — raw (rating, count) rows for the histogram. Returned as {@code Object[]} rather
     * than a JPQL constructor expression because {@code Review.rating} is a {@code Short}
     * and {@code count(r)} is a {@code Long} — Hibernate's constructor-expression matcher
     * does the widening in Java, not in JPQL, so the mapping is done by hand in
     * {@link com.secureleaf.marketplace.service.ReviewService}.
     */
    @Query("select r.rating, count(r) from Review r where r.product.id = :productId group by r.rating")
    List<Object[]> countByRatingForProduct(@Param("productId") Long productId);
}
