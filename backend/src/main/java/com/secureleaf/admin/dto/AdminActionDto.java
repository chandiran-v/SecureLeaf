package com.secureleaf.admin.dto;

import java.time.OffsetDateTime;

/** D7 — the GraphQL {@code AdminAction} type. */
public record AdminActionDto(
        Long id,
        AdminActorDto admin,
        String action,
        String targetType,
        Long targetId,
        String reason,
        OffsetDateTime createdAt
) {}
