package com.secureleaf.commerce.entity;

import com.secureleaf.auth.entity.User;
import com.secureleaf.common.entity.BaseEntity;
import com.secureleaf.common.exception.BusinessException;
import com.secureleaf.common.exception.ErrorCode;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "orders")
@Getter
@Setter
public class Order extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "buyer_id", nullable = false)
    private User buyer;

    @Column(name = "total_amount_paise", nullable = false)
    private Long totalAmountPaise;

    /**
     * No public setter: status only changes through {@link #transitionTo}, which
     * enforces the state machine in {@link OrderStatus#canTransitionTo} (D5).
     */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    @Setter(AccessLevel.NONE)
    private OrderStatus status = OrderStatus.PENDING;

    /** Client-supplied key that makes initiateOrder safe to retry (D2). Null for legacy rows. */
    @Column(name = "idempotency_key", unique = true)
    private String idempotencyKey;

    /** The gateway's id for this order (Razorpay "order_XXXX"). Null for free orders (D10). */
    @Column(name = "gateway_order_id", unique = true)
    private String gatewayOrderId;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    public void transitionTo(OrderStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Order " + id + " cannot move from " + status + " to " + next);
        }
        this.status = next;
    }

    /** MVP orders hold exactly one item (single-product checkout). */
    public OrderItem getSingleItem() {
        return items.get(0);
    }
}
