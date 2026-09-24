package com.secureleaf.creator.entity;

import com.secureleaf.auth.entity.User;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "creator_payouts")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
public class CreatorPayout {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "creator_id", nullable = false)
    private User creator;

    @Column(name = "amount_paise", nullable = false)
    private Long amountPaise;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private PayoutStatus status = PayoutStatus.REQUESTED;

    @Column(name = "gross_revenue_paise", nullable = false)
    private Long grossRevenuePaise;

    @Column(name = "platform_fee_paise", nullable = false)
    private Long platformFeePaise;

    @Column(name = "net_payout_paise", nullable = false)
    private Long netPayoutPaise;

    @Column(name = "payout_method", length = 50)
    private String payoutMethod;

    @Column(name = "payout_reference")
    private String payoutReference;

    @CreatedDate
    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by")
    private User approvedBy;

}
