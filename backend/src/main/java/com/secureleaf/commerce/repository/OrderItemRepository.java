package com.secureleaf.commerce.repository;

import com.secureleaf.commerce.dto.CreatorEarningsDto;
import com.secureleaf.commerce.dto.ProductStatsDto;
import com.secureleaf.commerce.entity.OrderItem;
import com.secureleaf.commerce.entity.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

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

    /**
     * Product.salesCount/netEarningsPaise (Phase 6, D2) — one aggregate query, grouped by
     * product, for every id in the batch. Products with zero completed sales simply have no row
     * in the result; callers treat "missing" the same as zero (see ProductStatsDto.empty).
     */
    @Query("""
            select new com.secureleaf.commerce.dto.ProductStatsDto(
                i.product.id,
                count(i),
                coalesce(sum(i.creatorEarningsPaise), 0L))
            from OrderItem i
            where i.product.id in :productIds
              and i.order.status = :status
            group by i.product.id
            """)
    List<ProductStatsDto> sumStatsByProductIdsAndStatus(@Param("productIds") Collection<Long> productIds,
                                                        @Param("status") OrderStatus status);

    default List<ProductStatsDto> sumStatsByProductIds(Collection<Long> productIds) {
        return sumStatsByProductIdsAndStatus(productIds, OrderStatus.COMPLETED);
    }
}
