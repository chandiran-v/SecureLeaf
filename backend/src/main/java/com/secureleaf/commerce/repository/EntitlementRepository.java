package com.secureleaf.commerce.repository;

import com.secureleaf.commerce.entity.Entitlement;
import com.secureleaf.commerce.entity.EntitlementStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EntitlementRepository extends JpaRepository<Entitlement, Long> {

    boolean existsByBuyerIdAndProductIdAndStatus(Long buyerId, Long productId, EntitlementStatus status);

    /**
     * The secure viewer's entry check (VIEW-01, VIEW-12): does this buyer hold an ACTIVE
     * entitlement for this product? The partial unique index from V4 guarantees at most one row.
     * Eagerly loads {@code documentVersion} — the viewer needs its {@code pageCount} immediately
     * (D8: the *entitled* version's page count, not necessarily the product's latest one).
     */
    @EntityGraph(attributePaths = {"documentVersion", "product", "buyer"})
    Optional<Entitlement> findByBuyerIdAndProductIdAndStatus(Long buyerId, Long productId, EntitlementStatus status);

    /**
     * My Library (PAY-09). No filter on product status or deleted_at: a buyer keeps
     * access to what they paid for even if the creator later unpublishes or deletes it.
     * One query, no pagination → fetching the `tags` collection is safe here.
     */
    @EntityGraph(attributePaths = {"product", "product.creator", "product.category", "product.tags"})
    List<Entitlement> findByBuyerIdAndStatusOrderByGrantedAtDesc(Long buyerId, EntitlementStatus status);

    /**
     * My Library (Phase 6, D1) — every entitlement the buyer has ever held, ACTIVE or not, newest
     * first. REVOKED/EXPIRED rows stay visible with their status so the buyer can see *why* a
     * product is no longer readable, rather than the row silently vanishing.
     */
    @EntityGraph(attributePaths = {"product", "product.creator", "product.category", "product.tags"})
    List<Entitlement> findByBuyerIdOrderByGrantedAtDesc(Long buyerId);

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

    /**
     * AdminUser.purchaseCount (Phase 8, D3) — every entitlement ever granted (any status), not
     * just ACTIVE ones: it answers "how many things has this buyer ever purchased", the same
     * intent as My Library showing REVOKED/EXPIRED rows rather than hiding them.
     */
    @Query("select e.buyer.id, count(e) from Entitlement e where e.buyer.id in :buyerIds group by e.buyer.id")
    List<Object[]> countByBuyerIdsGrouped(@Param("buyerIds") Collection<Long> buyerIds);
}
