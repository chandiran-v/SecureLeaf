package com.secureleaf.admin.repository;

import com.secureleaf.admin.entity.AdminAction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Append-only (D7) — same shape as {@code PaymentEventRepository}: only inherited save() and
 * reads are exposed, and the V7 migration's trigger rejects UPDATE/DELETE regardless.
 */
public interface AdminActionRepository extends JpaRepository<AdminAction, Long> {

    /**
     * {@code admin} is a plain {@code @ManyToOne} (no collection), so pagination and
     * {@code @EntityGraph} combine safely here — the same reasoning as
     * {@code ReviewRepository.findByProductIdOrderByCreatedAtDesc}.
     */
    @EntityGraph(attributePaths = "admin")
    Page<AdminAction> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
