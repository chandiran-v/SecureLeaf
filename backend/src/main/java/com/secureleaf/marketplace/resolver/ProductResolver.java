package com.secureleaf.marketplace.resolver;

import com.secureleaf.auth.service.SecureLeafUserDetails;
import com.secureleaf.marketplace.dto.CreateProductInput;
import com.secureleaf.marketplace.dto.ProductDto;
import com.secureleaf.marketplace.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;

import java.util.List;

/**
 * GraphQL resolver for product operations (Creator role only for mutations).
 *
 * Security layers:
 *   1. @PreAuthorize("hasRole('CREATOR')") — verified at method entry; Spring Security
 *      checks the current JWT's authorities for "ROLE_CREATOR".
 *   2. ProductService.assertOwnership() — per-record check inside the service;
 *      ensures the caller owns the specific product being mutated.
 *
 * Public marketplace queries (products, product) are intentionally left
 * unimplemented here — they belong to Phase 3.
 */
@Controller
@RequiredArgsConstructor
public class ProductResolver {

    private final ProductService productService;

    // ── Mutations ────────────────────────────────────────────────────────────

    @MutationMapping
    @PreAuthorize("hasRole('CREATOR')")
    public ProductDto createProduct(@Argument CreateProductInput input) {
        return productService.createProduct(input, getCurrentUserId());
    }

    @MutationMapping
    @PreAuthorize("hasRole('CREATOR')")
    public ProductDto unpublishProduct(@Argument Long id) {
        return productService.unpublishProduct(id, getCurrentUserId());
    }

    @MutationMapping
    @PreAuthorize("hasRole('CREATOR')")
    public boolean deleteProduct(@Argument Long id) {
        productService.deleteProduct(id, getCurrentUserId());
        return true;
    }

    // ── Queries ──────────────────────────────────────────────────────────────

    @QueryMapping
    @PreAuthorize("hasRole('CREATOR')")
    public List<ProductDto> myProducts() {
        return productService.myProducts(getCurrentUserId());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Long getCurrentUserId() {
        SecureLeafUserDetails userDetails = (SecureLeafUserDetails) SecurityContextHolder
                .getContext().getAuthentication().getPrincipal();
        return userDetails.getUserId();
    }
}
