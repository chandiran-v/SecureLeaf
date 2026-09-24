package com.secureleaf.notification.repository;

import com.secureleaf.notification.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /** Newest 20 for the bell dropdown — served by idx_notifications_recipient_id. */
    List<Notification> findTop20ByRecipientIdOrderByCreatedAtDescIdDesc(Long recipientId);

    /** Recipient-scoped: another user's notification id is simply not found (BOLA → 404). */
    Optional<Notification> findByIdAndRecipientId(Long id, Long recipientId);
}
