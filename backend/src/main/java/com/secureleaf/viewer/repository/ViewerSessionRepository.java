package com.secureleaf.viewer.repository;

import com.secureleaf.viewer.entity.ViewerSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ViewerSessionRepository extends JpaRepository<ViewerSession, Long> {

    Optional<ViewerSession> findBySessionTokenHash(String sessionTokenHash);

    /** D4 — the sweeper: rows still open whose lease has lapsed. */
    @Query("select s from ViewerSession s where s.endedAt is null and s.lastHeartbeatAt < :cutoff")
    List<ViewerSession> findExpiredLeases(@Param("cutoff") Instant cutoff);

    /** Admin suspendUser (Phase 8, D4) — every session this user currently has open, across
     *  every product, so all of them can be ended with reason REVOKED. */
    @Query("select s from ViewerSession s where s.user.id = :userId and s.endedAt is null")
    List<ViewerSession> findActiveByUserId(@Param("userId") Long userId);
}
