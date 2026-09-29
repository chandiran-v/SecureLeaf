package com.secureleaf.creator.repository;

import com.secureleaf.creator.entity.CreatorPayout;
import com.secureleaf.creator.entity.PayoutStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Status is always a bound parameter, never a JPQL enum literal — see OrderRepository.findOpenOrders
 * for why (Hibernate renders literals with the Java class name as the Postgres type).
 */
public interface CreatorPayoutRepository extends JpaRepository<CreatorPayout, Long> {

    /** Admin actions serialise on the payout row, so two admins can't approve/reject it twice. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from CreatorPayout p join fetch p.creator where p.id = :id")
    Optional<CreatorPayout> findByIdForUpdate(@Param("id") Long id);

    /** D1 — everything the creator has asked for that still counts against the balance. */
    @Query("select coalesce(sum(p.amountPaise), 0L) from CreatorPayout p where p.creator.id = :creatorId and p.status <> :excluded")
    long sumAmountExcludingStatus(@Param("creatorId") Long creatorId, @Param("excluded") PayoutStatus excluded);

    @Query("select coalesce(sum(p.amountPaise), 0L) from CreatorPayout p where p.creator.id = :creatorId and p.status = :status")
    long sumAmountWithStatus(@Param("creatorId") Long creatorId, @Param("status") PayoutStatus status);

    long countByCreatorIdAndStatusIn(Long creatorId, Collection<PayoutStatus> statuses);

    @EntityGraph(attributePaths = "creator")
    List<CreatorPayout> findByCreatorIdOrderByIdDesc(Long creatorId);

    @EntityGraph(attributePaths = "creator")
    Page<CreatorPayout> findByStatus(PayoutStatus status, Pageable pageable);

    @Override
    @EntityGraph(attributePaths = "creator")
    Page<CreatorPayout> findAll(Pageable pageable);

    /** D4 — payouts marked PAID inside a statement month. */
    @EntityGraph(attributePaths = "creator")
    List<CreatorPayout> findByCreatorIdAndStatusAndProcessedAtGreaterThanEqualAndProcessedAtLessThanOrderByProcessedAt(
            Long creatorId, PayoutStatus status, Instant from, Instant to);
}
