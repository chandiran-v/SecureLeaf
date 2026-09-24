package com.secureleaf.marketplace.repository;

import com.secureleaf.marketplace.entity.Product;
import com.secureleaf.marketplace.entity.ProductStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
