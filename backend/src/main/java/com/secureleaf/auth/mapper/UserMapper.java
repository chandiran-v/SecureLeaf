package com.secureleaf.auth.mapper;

import com.secureleaf.auth.dto.UserDto;
import com.secureleaf.auth.entity.User;
import com.secureleaf.auth.entity.UserRole;
import java.time.ZoneOffset;
import java.util.stream.Collectors;

public class UserMapper {
    public static UserDto toDto(User user) {
        if (user == null) return null;
        return new UserDto(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getRoles().stream().map(UserRole::getRole).collect(Collectors.toSet()),
                user.getCreatedAt() != null ? user.getCreatedAt().atOffset(ZoneOffset.UTC) : null
        );
    }
}
