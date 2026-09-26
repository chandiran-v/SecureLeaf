package com.secureleaf.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends the password-reset email after the transaction that minted the token commits (D10).
 *
 * WHY AFTER_COMMIT + @Async — same reasoning as {@code NotificationDispatcher} (Phase 4, D9):
 * the token row is written inside {@code requestPasswordReset}'s transaction. If that rolled
 * back, the email must never go out for a token that doesn't exist; and SMTP latency must never
 * make the (enumeration-safe, always-true) mutation response slow.
 *
 * Unlike {@code NotificationDispatcher}, this send is NOT gated behind
 * {@code notifications.email.enabled} — a password reset email is core account-recovery
 * functionality, not an optional notification, so it always attempts to send. Tests replace the
 * {@link JavaMailSender} bean itself (see {@code RecordingMailSender}) rather than relying on a
 * flag to avoid real SMTP.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PasswordResetMailer {

    private final JavaMailSender mailSender;

    @Value("${notifications.email.from:no-reply@secureleaf.local}")
    private String fromAddress;

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPasswordResetRequested(PasswordResetMailRequestedEvent event) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromAddress);
            message.setTo(event.recipientEmail());
            message.setSubject(event.subject());
            message.setText(event.body());
            mailSender.send(message);
        } catch (Exception e) {
            log.warn("Password reset email failed for {}: {}", event.recipientEmail(), e.getMessage());
        }
    }
}
