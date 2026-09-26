package com.secureleaf.admin.entity;

import com.secureleaf.auth.entity.User;
import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * D7 — one append-only row per admin mutation (suspend/reactivate a user, take down/restore a
 * product), written in the same transaction as the change it records (see
 * {@link com.secureleaf.admin.service.AdminAuditService}). The V7 migration's trigger
 * (mirrors {@code payment_events}, V4) rejects any UPDATE/DELETE at the database level, so this
 * log can't be edited even by a bug, only appended to.
 */
@Entity
@Table(name = "admin_actions")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
public class AdminAction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "admin_id", nullable = false)
    private User admin;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private AdminActionType action;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 50)
    private AdminTargetType targetType;

    @Column(name = "target_id")
    private Long targetId;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

}
