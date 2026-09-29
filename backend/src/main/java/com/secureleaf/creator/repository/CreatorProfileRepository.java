package com.secureleaf.creator.repository;

import com.secureleaf.creator.entity.CreatorProfile;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CreatorProfileRepository extends JpaRepository<CreatorProfile, Long> {

    /**
     * Phase 09C D2 — {@code SELECT … FOR UPDATE} on the creator's profile row: a per-creator mutex
     * for payout requests. Two simultaneous requests would otherwise both read the same balance
     * and both pass ("check-then-act"). Same pattern as UserRepository.findByIdForUpdate (Phase 4).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from CreatorProfile p where p.userId = :userId")
    Optional<CreatorProfile> findByIdForUpdate(@Param("userId") Long userId);

    /**
     * Creators made before this phase (or by tests) may have no profile row yet. INSERT … ON
     * CONFLICT DO NOTHING creates it if missing and is harmless if a concurrent request already did,
     * so the row we lock next always exists.
     */
    @Modifying
    @Query(value = "INSERT INTO creator_profiles (user_id) VALUES (:userId) ON CONFLICT (user_id) DO NOTHING",
            nativeQuery = true)
    void insertIfMissing(@Param("userId") Long userId);
}
