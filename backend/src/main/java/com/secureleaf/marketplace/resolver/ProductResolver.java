package com.secureleaf.marketplace.resolver;

import com.secureleaf.auth.service.SecureLeafUserDetails;
import com.secureleaf.marketplace.dto.CreateProductInput;
import com.secureleaf.marketplace.dto.ProductDto;
import com.secureleaf.marketplace.dto.ProductFilterInput;
import com.secureleaf.marketplace.dto.ProductPageDto;
import com.secureleaf.marketplace.service.ProductSearchService;
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
 * GraphQL resolver for product operations.
 *
 * Security layers for the CREATOR-only mutations:
 *   1. @PreAuthorize("hasRole('CREATOR')") — verified at method entry; Spring Security
 *      checks the current JWT's authorities for "ROLE_CREATOR".
 *   2. ProductService.assertOwnership() — per-record check inside the service;
 *      ensures the caller owns the specific product being mutated.
 *
 * {@code products} and {@code product} carry no @PreAuthorize — they are the public
 * marketplace surface. /graphql is permitAll at the HTTP level (SecurityConfig), so
 * these resolve for anonymous visitors; LIVE-only visibility is enforced in
 * ProductSearchService/SQL, not here.
 */
@Controller
@RequiredArgsConstructor
public class ProductResolver {

    private final ProductService productService;
    private final ProductSearchService productSearchService;

    // ── Public marketplace queries ──────────────────────────────────────────

    @QueryMapping
    public ProductPageDto products(@Argument ProductFilterInput filter,
                                    @Argument int page,
                                    @Argument int size) {
        return productSearchService.searchProducts(filter, page, size);
    }

    @QueryMapping
    public ProductDto product(@Argument Long id) {
        return productSearchService.getLiveProduct(id);
    }

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
