package com.secureleaf.auth.service;

/**
 * Raised when {@link PasswordResetService} decides an email should go out — either the reset
 * link itself, or the "you sign in with Google" notice. Same after-commit/async shape as
 * {@code NotificationCreatedEvent} (Phase 4, D9): carries the fully-composed subject/body so the
 * listener doesn't need to re-open a transaction to build it.
 */
public record PasswordResetMailRequestedEvent(String recipientEmail, String subject, String body) {}
