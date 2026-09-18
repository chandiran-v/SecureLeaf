package com.secureleaf.viewer.entity;

import com.secureleaf.auth.entity.User;
import com.secureleaf.commerce.entity.Entitlement;
import com.secureleaf.marketplace.entity.Product;
import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "viewer_sessions")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
public class ViewerSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "entitlement_id", nullable = false)
    private Entitlement entitlement;

    @Column(name = "session_token_hash", nullable = false, unique = true)
    private String sessionTokenHash;

    @Column(name = "device_fingerprint")
    private String deviceFingerprint;

    @Column(name = "ip_address", columnDefinition = "inet")
    private String ipAddress;

    @CreatedDate
    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "last_heartbeat_at", nullable = false)
    private Instant lastHeartbeatAt = Instant.now();

    @Column(name = "ended_at")
    private Instant endedAt;

}
