package com.secureleaf.admin.dto;

import com.secureleaf.auth.entity.AccountStatus;
import com.secureleaf.auth.entity.Role;

/** Mirrors the GraphQL {@code AdminUserFilter} input type. {@code search} matches email or
 *  display name, case-insensitive (D3). */
public record AdminUserFilterDto(String search, Role role, AccountStatus status) {}
