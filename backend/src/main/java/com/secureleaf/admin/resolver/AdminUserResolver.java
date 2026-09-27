package com.secureleaf.admin.resolver;

import com.secureleaf.admin.dto.AdminUserDto;
import com.secureleaf.admin.dto.AdminUserFilterDto;
import com.secureleaf.admin.dto.AdminUserPageDto;
import com.secureleaf.admin.service.AdminUserService;
import com.secureleaf.auth.service.SecureLeafUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;

/**
 * D2/D3/D4 — every method here is {@code @PreAuthorize("hasRole('ADMIN')")}; a BUYER or
 * CREATOR calling any of them gets ACCESS_DENIED (acceptance criterion 1). Object-level rules
 * beyond "is this caller an ADMIN" (self-suspend, admin-suspend) live in
 * {@link AdminUserService}, the same split {@code ProductResolver}/{@code ProductService} use
 * for CREATOR ownership checks.
 */
@Controller
@RequiredArgsConstructor
public class AdminUserResolver {

    private final AdminUserService adminUserService;

    @QueryMapping
    @PreAuthorize("hasRole('ADMIN')")
    public AdminUserPageDto adminUsers(@Argument AdminUserFilterDto filter, @Argument int page, @Argument int size) {
        return adminUserService.adminUsers(filter, page, size);
    }

    @MutationMapping
    @PreAuthorize("hasRole('ADMIN')")
    public AdminUserDto suspendUser(@Argument Long userId, @Argument String reason) {
        return adminUserService.suspendUser(getCurrentUserId(), userId, reason);
    }

    @MutationMapping
    @PreAuthorize("hasRole('ADMIN')")
    public AdminUserDto reactivateUser(@Argument Long userId) {
        return adminUserService.reactivateUser(getCurrentUserId(), userId);
    }

    private Long getCurrentUserId() {
        SecureLeafUserDetails userDetails = (SecureLeafUserDetails) SecurityContextHolder
                .getContext().getAuthentication().getPrincipal();
        return userDetails.getUserId();
    }
}
