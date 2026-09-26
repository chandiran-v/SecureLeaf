package com.secureleaf.marketplace.repository;

import com.secureleaf.marketplace.entity.Product;
import com.secureleaf.marketplace.entity.ProductStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long>, ProductSearchRepository {

    /**
     * Eagerly loads creator and category alongside the products to avoid N+1
     * queries when mapping the full ProductDto list for myProducts.
     *
     * N+1 problem: if we didn't do this, fetching 20 products would cause
     * 20 extra SELECT queries for creator, and 20 more for category = 41 total.
     * With @EntityGraph, it's 1 query with JOINs.
     */
    @EntityGraph(attributePaths = {"creator", "category", "tags"})
    List<Product> findByCreatorIdAndDeletedAtIsNull(Long creatorId);

    boolean existsBySlug(String slug);

    @EntityGraph(attributePaths = {"creator", "category", "tags"})
    Optional<Product> findByIdAndDeletedAtIsNull(Long id);

    /**
     * Step 3 of the two-step paging recipe (D2): one query, no LIMIT/OFFSET, so
     * Hibernate is happy fetching the `tags` collection via @EntityGraph. The
     * returned list's order follows the JOIN plan, NOT `ids`'s order — callers
     * (see {@link com.secureleaf.marketplace.service.ProductSearchService}) must
     * re-order it themselves.
     */
    @EntityGraph(attributePaths = {"creator", "category", "tags"})
    List<Product> findAllByIdIn(List<Long> ids);

    /** Public detail lookup — LIVE and not soft-deleted only, enforced in SQL (D4). */
    @EntityGraph(attributePaths = {"creator", "category", "tags"})
    Optional<Product> findByIdAndStatusAndDeletedAtIsNull(Long id, ProductStatus status);

    /**
     * Atomic counter increment (D8) — one SQL statement, evaluated by Postgres under
     * its own row lock, so concurrent purchases can never lose an update.
     *
     * The tempting alternative — {@code product.setTotalSales(product.getTotalSales() + 1)}
     * — is read-modify-write: two transactions both read 5 and both write 6. It would
     * also bump {@code Product.@Version}, so the loser would fail with an optimistic-lock
     * exception and roll back a purchase the buyer already paid for.
     *
     * A JPQL bulk UPDATE bypasses the persistence context (and @Version) entirely.
     * flushAutomatically = true pushes any pending entity changes first so they are
     * not reordered after this statement.
     */
    @Modifying(flushAutomatically = true)
    @Query("update Product p set p.totalSales = p.totalSales + 1 where p.id = :id")
    int incrementTotalSales(@Param("id") Long id);

    /**
     * Review aggregate correctness under concurrency (REV-04, D4, step 1) — locks the
     * product row for the rest of the caller's transaction. Every submitReview/deleteMyReview
     * call for the SAME product takes this lock first, so two buyers reviewing the same
     * product at the same time are serialised here instead of racing to read-then-write the
     * aggregate independently (the classic lost-update anomaly this design note calls out).
     * Other products are unaffected — this locks one row, not the table.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id = :id")
    Optional<Product> findByIdForUpdate(@Param("id") Long id);

    /**
     * Review aggregate correctness (D4, step 3) — recomputed from the reviews table, not
     * incremented, so an edited or deleted review is reflected exactly, not approximated.
     * {@code average_rating} becomes NULL once the last review is removed (the CHECK
     * constraint on that column allows NULL — it only rejects a non-NULL value outside
     * 1.00–5.00).
     *
     * A native, @Modifying bulk UPDATE bypasses the JPA persistence context and the
     * {@code @Version} column entirely — the same choice ProductRepository.incrementTotalSales
     * already makes for the same reason: this is a single self-contained SQL statement, not a
     * read-modify-write through the entity, so there is nothing for optimistic locking to
     * protect and nothing to accidentally increment. Safe here specifically because
     * {@link #findByIdForUpdate} has already taken the pessimistic lock this method relies on —
     * without that lock, a bare bulk UPDATE would still be individually atomic but two of them
     * could still interleave with the review INSERT/UPDATE/DELETE in a way that skips an update.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE products
            SET average_rating = (SELECT ROUND(AVG(rating)::numeric, 2) FROM reviews WHERE product_id = :id),
                review_count    = (SELECT COUNT(*) FROM reviews WHERE product_id = :id)
            WHERE id = :id
            """, nativeQuery = true)
    int recalculateReviewAggregate(@Param("id") Long id);
}
