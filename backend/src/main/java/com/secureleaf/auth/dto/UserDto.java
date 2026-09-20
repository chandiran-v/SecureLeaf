package com.secureleaf.auth.dto;

import com.secureleaf.auth.entity.Role;
import java.time.OffsetDateTime;
import java.util.Set;

public record UserDto(Long id, String email, String displayName, Set<Role> roles, OffsetDateTime createdAt) {}
