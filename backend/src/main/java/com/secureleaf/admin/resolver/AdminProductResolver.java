package com.secureleaf.admin.resolver;

import com.secureleaf.admin.dto.AdminProductDto;
import com.secureleaf.admin.dto.AdminProductFilterDto;
import com.secureleaf.admin.dto.AdminProductPageDto;
import com.secureleaf.admin.service.AdminProductService;
import com.secureleaf.auth.service.SecureLeafUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;

/** D2/D5 — see {@link AdminUserResolver}'s javadoc for the authorisation split this mirrors. */
@Controller
@RequiredArgsConstructor
public class AdminProductResolver {

    private final AdminProductService adminProductService;

    @QueryMapping
    @PreAuthorize("hasRole('ADMIN')")
    public AdminProductPageDto adminProducts(@Argument AdminProductFilterDto filter, @Argument int page, @Argument int size) {
        return adminProductService.adminProducts(filter, page, size);
    }

    @MutationMapping
    @PreAuthorize("hasRole('ADMIN')")
    public AdminProductDto takeDownProduct(@Argument Long productId, @Argument String reason) {
        return adminProductService.takeDownProduct(getCurrentUserId(), productId, reason);
    }

    @MutationMapping
    @PreAuthorize("hasRole('ADMIN')")
    public AdminProductDto restoreProduct(@Argument Long productId) {
        return adminProductService.restoreProduct(getCurrentUserId(), productId);
    }

    private Long getCurrentUserId() {
        SecureLeafUserDetails userDetails = (SecureLeafUserDetails) SecurityContextHolder
                .getContext().getAuthentication().getPrincipal();
        return userDetails.getUserId();
    }
}
