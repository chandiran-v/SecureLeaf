package com.secureleaf.admin.mapper;

import com.secureleaf.admin.dto.AdminUserDto;
import com.secureleaf.auth.entity.Role;
import com.secureleaf.auth.entity.User;
import com.secureleaf.auth.entity.UserRole;

import java.time.ZoneOffset;
import java.util.stream.Collectors;

/**
 * Static mapper, same discipline as {@code ProductMapper}/{@code UserMapper}: must be called
 * from inside a @Transactional method because {@code user.getRoles()} is lazy.
 */
public class AdminUserMapper {

    private AdminUserMapper() {}

    public static AdminUserDto toDto(User user, int productCount, int purchaseCount) {
        if (user == null) return null;
        return new AdminUserDto(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getRoles().stream().map(UserRole::getRole).collect(Collectors.toSet()),
                user.getAccountStatus(),
                user.getAuthProvider(),
                user.getCreatedAt() != null ? user.getCreatedAt().atOffset(ZoneOffset.UTC) : null,
                productCount,
                purchaseCount
        );
    }

    /** Convenience overload for a caller (e.g. suspend/reactivate) with no batched counts to hand. */
    public static AdminUserDto toDto(User user) {
        return toDto(user, 0, 0);
    }
}
