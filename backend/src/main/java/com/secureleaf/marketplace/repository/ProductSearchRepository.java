package com.secureleaf.marketplace.repository;

import com.secureleaf.marketplace.dto.ProductFilterInput;
import org.springframework.data.domain.Pageable;

/**
 * Custom repository fragment for public marketplace search.
 *
 * Spring Data composes this into {@link ProductRepository} by name convention:
 * ProductRepository extends ProductSearchRepository, and Spring finds
 * ProductSearchRepositoryImpl automatically (the "Impl" suffix convention).
 *
 * Returns only ids + total (step 1 + 2 of the two-step paging recipe, see D2 in the
 * phase-3 design doc) — deliberately NOT {@code Page<Product>}. Fetching the full
 * entities (step 3, with their tags collection) happens in
 * {@link com.secureleaf.marketplace.service.ProductSearchService} via
 * {@link ProductRepository#findAllByIdIn}, a sibling method on the *same* repository
 * interface. Doing step 3 here too would mean this bean depends on ProductRepository,
 * which depends on this bean to exist — a circular dependency Spring Data can't resolve
 * during proxy creation.
 */
public interface ProductSearchRepository {

    ProductIdPage searchLiveProductIds(ProductFilterInput filter, Pageable pageable);
}
