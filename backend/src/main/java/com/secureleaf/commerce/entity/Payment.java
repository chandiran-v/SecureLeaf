package com.secureleaf.commerce.entity;

import com.secureleaf.common.entity.BaseEntity;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import com.secureleaf.common.exception.BusinessException;
import com.secureleaf.common.exception.ErrorCode;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "payments")
@Getter
@Setter
public class Payment extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false, unique = true)
    private Order order;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "provider_payment_id")
    private String providerPaymentId;

    @Column(name = "provider_name", nullable = false, length = 50)
    private String providerName = "MOCK";

    @Column(name = "amount_paise", nullable = false)
    private Long amountPaise;

    /**
     * No public setter: status only changes through {@link #transitionTo}, which enforces
     * {@link PaymentStatus#canTransitionTo} (D5). Callers must also append a
     * {@link PaymentEvent} for every transition — PaymentCompletionService does both together.
     */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    @Setter(AccessLevel.NONE)
    private PaymentStatus status = PaymentStatus.PENDING;

    /** Reason for the latest declined attempt, shown on the checkout page. */
    @Column(name = "failure_reason")
    private String failureReason;

    public void transitionTo(PaymentStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Payment " + id + " cannot move from " + status + " to " + next);
        }
        this.status = next;
    }

}
