package com.secureleaf.notification.service;

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
 * Runs the side effects of a new notification — Redis publish + email — only AFTER the
 * purchase transaction has committed, on a background thread (D9).
 *
 * WHY AFTER_COMMIT
 * The notification row is written inside the purchase transaction. If that transaction later
 * rolls back (say the entitlement insert hits the unique index), the row disappears — but an
 * email already sent can't be un-sent. {@code @TransactionalEventListener(AFTER_COMMIT)} holds
 * the event until the commit succeeds, and drops it silently on rollback.
 *
 * WHY @Async
 * SMTP can take seconds or hang. The buyer's "payment confirmed" response must not wait for
 * an email server. The listener runs on the {@code notificationExecutor} pool instead.
 *
 * The honest gap: if the JVM dies between commit and this method, the email is never sent
 * (the row is still there, so the in-app notification survives). The industry fix is the
 * Transactional Outbox pattern — see the Phase 4 note, section 10.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationDispatcher {

    private final NotificationPublisher publisher;
    private final JavaMailSender mailSender;

    @Value("${notifications.email.enabled:false}")
    private boolean emailEnabled;

    @Value("${notifications.email.from:no-reply@secureleaf.local}")
    private String fromAddress;

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onNotificationCreated(NotificationCreatedEvent event) {
        // Each side effect is isolated: Redis being down must not stop the email, and
        // neither failure can affect the purchase, which is already committed.
        try {
            publisher.publish(event);
        } catch (Exception e) {
            log.warn("Pub/Sub publish failed for notification {}: {}", event.notificationId(), e.getMessage());
        }

        if (emailEnabled && event.recipientEmail() != null) {
            try {
                SimpleMailMessage message = new SimpleMailMessage();
                message.setFrom(fromAddress);
                message.setTo(event.recipientEmail());
                message.setSubject(event.title());
                message.setText(event.body());
                mailSender.send(message);
            } catch (Exception e) {
                log.warn("Email failed for notification {}: {}", event.notificationId(), e.getMessage());
            }
        }
    }
}
