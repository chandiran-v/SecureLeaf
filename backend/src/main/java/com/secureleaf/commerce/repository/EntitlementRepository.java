package com.secureleaf.commerce.repository;

import com.secureleaf.commerce.entity.Entitlement;
import com.secureleaf.commerce.entity.EntitlementStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface EntitlementRepository extends JpaRepository<Entitlement, Long> {

    boolean existsByBuyerIdAndProductIdAndStatus(Long buyerId, Long productId, EntitlementStatus status);

    /**
     * My Library (PAY-09). No filter on product status or deleted_at: a buyer keeps
     * access to what they paid for even if the creator later unpublishes or deletes it.
     * One query, no pagination → fetching the `tags` collection is safe here.
     */
    @EntityGraph(attributePaths = {"product", "product.creator", "product.category", "product.tags"})
    List<Entitlement> findByBuyerIdAndStatusOrderByGrantedAtDesc(Long buyerId, EntitlementStatus status);

    /**
     * Product.ownedByMe batch loader (D11) — one query for a whole page of products.
     * Status passed as a parameter, not a JPQL enum literal — see OrderRepository.findOpenOrders.
     */
    @Query("""
            select e.product.id from Entitlement e
            where e.buyer.id = :buyerId
              and e.status = :status
              and e.product.id in :productIds
            """)
    List<Long> findProductIdsByBuyerAndStatus(@Param("buyerId") Long buyerId,
                                              @Param("status") EntitlementStatus status,
                                              @Param("productIds") Collection<Long> productIds);

    default List<Long> findOwnedProductIds(Long buyerId, Collection<Long> productIds) {
        return findProductIdsByBuyerAndStatus(buyerId, EntitlementStatus.ACTIVE, productIds);
    }
}
