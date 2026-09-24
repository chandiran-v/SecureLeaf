package com.secureleaf.notification.dto;

import java.time.OffsetDateTime;

/** GraphQL {@code Notification}. */
public record NotificationDto(
        Long id,
        String type,
        String title,
        String body,
        boolean isRead,
        OffsetDateTime createdAt
) {}
