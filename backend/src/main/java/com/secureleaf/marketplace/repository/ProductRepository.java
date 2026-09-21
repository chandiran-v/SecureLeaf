package com.secureleaf.marketplace.repository;

import com.secureleaf.marketplace.entity.Product;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

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
}
