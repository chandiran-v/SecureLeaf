package com.secureleaf.creator.resolver;

import com.secureleaf.auth.service.SecureLeafUserDetails;
import com.secureleaf.creator.service.ProductRecoveryService;
import com.secureleaf.marketplace.dto.ProductDto;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;

/** GraphQL entry points for {@code retryProcessing}/{@code republishProduct} (Phase 6, D4). */
@Controller
@RequiredArgsConstructor
public class ProductRecoveryResolver {

    private final ProductRecoveryService productRecoveryService;

    @MutationMapping
    @PreAuthorize("hasRole('CREATOR')")
    public ProductDto retryProcessing(@Argument Long productId) {
        return productRecoveryService.retryProcessing(productId, getCurrentUserId());
    }

    @MutationMapping
    @PreAuthorize("hasRole('CREATOR')")
    public ProductDto republishProduct(@Argument Long productId) {
        return productRecoveryService.republishProduct(productId, getCurrentUserId());
    }

    private Long getCurrentUserId() {
        SecureLeafUserDetails userDetails = (SecureLeafUserDetails) SecurityContextHolder
                .getContext().getAuthentication().getPrincipal();
        return userDetails.getUserId();
    }
}
