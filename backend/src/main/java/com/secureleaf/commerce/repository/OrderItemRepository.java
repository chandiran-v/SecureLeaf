package com.secureleaf.commerce.repository;

import com.secureleaf.commerce.dto.CreatorEarningsDto;
import com.secureleaf.commerce.entity.OrderItem;
import com.secureleaf.commerce.entity.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    /**
     * PAY-10 — one aggregate query, computed by the database from the snapshotted split
     * (D7). Summing stored snapshots instead of recomputing `price × 10%` means a future
     * change to the commission rate cannot rewrite historical earnings.
     *
     * Status passed as a parameter, not a JPQL enum literal — see OrderRepository.findOpenOrders.
     */
    @Query("""
            select new com.secureleaf.commerce.dto.CreatorEarningsDto(
                count(i),
                coalesce(sum(i.pricePaise), 0L),
                coalesce(sum(i.platformFeePaise), 0L),
                coalesce(sum(i.creatorEarningsPaise), 0L))
            from OrderItem i
            where i.product.creator.id = :creatorId
              and i.order.status = :status
            """)
    CreatorEarningsDto sumEarningsForCreatorAndStatus(@Param("creatorId") Long creatorId,
                                                      @Param("status") OrderStatus status);

    default CreatorEarningsDto sumEarningsForCreator(Long creatorId) {
        return sumEarningsForCreatorAndStatus(creatorId, OrderStatus.COMPLETED);
    }
}
