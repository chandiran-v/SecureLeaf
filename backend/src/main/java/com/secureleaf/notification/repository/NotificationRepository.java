package com.secureleaf.notification.repository;

import com.secureleaf.notification.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /** Newest 20 for the bell dropdown — served by idx_notifications_recipient_id. */
    List<Notification> findTop20ByRecipientIdOrderByCreatedAtDescIdDesc(Long recipientId);

    /** Recipient-scoped: another user's notification id is simply not found (BOLA → 404). */
    Optional<Notification> findByIdAndRecipientId(Long id, Long recipientId);

    /**
     * D8 — "Mark all read" in one UPDATE rather than loading every row into the persistence
     * context and flipping each one (which would also be a read-modify-write on every row's
     * data, unnecessary here since only two columns ever change).
     */
    @Modifying(flushAutomatically = true)
    @Query("update Notification n set n.isRead = true, n.readAt = :readAt where n.recipient.id = :recipientId and n.isRead = false")
    int markAllReadForRecipient(@Param("recipientId") Long recipientId, @Param("readAt") Instant readAt);
}
