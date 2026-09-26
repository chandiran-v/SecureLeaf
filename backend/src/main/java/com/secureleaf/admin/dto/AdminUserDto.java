package com.secureleaf.admin.dto;

import com.secureleaf.auth.entity.AccountStatus;
import com.secureleaf.auth.entity.AuthProvider;
import com.secureleaf.auth.entity.Role;

import java.time.OffsetDateTime;
import java.util.Set;

/** D3 — the GraphQL {@code AdminUser} type. See {@code UserDto}'s javadoc for why this is a
 *  separate record rather than adding these fields to it: they must never leak into the public
 *  {@code User}/{@code CreatorSummary} projections. */
public record AdminUserDto(
        Long id,
        String email,
        String displayName,
        Set<Role> roles,
        AccountStatus accountStatus,
        AuthProvider authProvider,
        OffsetDateTime createdAt,
        int productCount,
        int purchaseCount
) {}
