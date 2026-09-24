package com.secureleaf.notification.service;

/**
 * Spring application event raised when a notification row is inserted (D9).
 *
 * It carries everything the after-commit side effects need (recipient, email, text), so the
 * listener doesn't have to open a transaction and re-read the row it just wrote.
 */
public record NotificationCreatedEvent(
        Long notificationId,
        Long recipientId,
        String recipientEmail,
        String type,
        String title,
        String body
) {}
