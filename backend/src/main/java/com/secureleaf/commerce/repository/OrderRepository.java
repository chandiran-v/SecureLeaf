package com.secureleaf.commerce.repository;

import com.secureleaf.commerce.entity.Order;
import com.secureleaf.commerce.entity.OrderStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /** Layer 1 of D2 — replay lookup for a retried initiateOrder. */
    @EntityGraph(attributePaths = {"items", "items.product"})
    Optional<Order> findByIdempotencyKey(String idempotencyKey);

    /**
     * Layer 2 of D2 — an open (PENDING) order this buyer already has for this product.
     *
     * The status is a bound PARAMETER, not a JPQL literal (`= OrderStatus.PENDING`). With
     * {@code @JdbcTypeCode(NAMED_ENUM)}, Hibernate renders an enum literal as
     * {@code 'PENDING'::OrderStatus} — using the Java class name as the Postgres type, while
     * the real type is {@code order_status} → "type orderstatus does not exist". Parameters are
     * sent untyped and Postgres infers the column's type, so they work.
     */
    @Query("""
            select o from Order o join o.items i
            where o.buyer.id = :buyerId
              and i.product.id = :productId
              and o.status = :status
            order by o.id desc
            """)
    List<Order> findByBuyerAndProductAndStatus(@Param("buyerId") Long buyerId,
                                               @Param("productId") Long productId,
                                               @Param("status") OrderStatus status);

    default List<Order> findOpenOrders(Long buyerId, Long productId) {
        return findByBuyerAndProductAndStatus(buyerId, productId, OrderStatus.PENDING);
    }

    /**
     * {@code SELECT … FOR UPDATE} on the order row (D3). Every path that completes or
     * fails a payment goes through this lock, so the browser's verifyPayment and the
     * gateway's webhook — which routinely arrive within milliseconds of each other —
     * are processed strictly one after the other. The second one then sees
     * status = COMPLETED and does nothing.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.gatewayOrderId = :gatewayOrderId")
    Optional<Order> findByGatewayOrderIdForUpdate(@Param("gatewayOrderId") String gatewayOrderId);

    /**
     * Buyer-scoped lookup: the buyer id is part of the WHERE clause, so another buyer's
     * order id simply isn't found (BOLA → NOT_FOUND, D12). `items.product.tags` is left
     * lazy on purpose — fetching two List collections (items + tags) in one query throws
     * MultipleBagFetchException; one order has one product, so the extra query is trivial.
     */
    @EntityGraph(attributePaths = {"items", "items.product", "items.product.creator", "items.product.category"})
    Optional<Order> findByIdAndBuyerId(Long id, Long buyerId);
}
