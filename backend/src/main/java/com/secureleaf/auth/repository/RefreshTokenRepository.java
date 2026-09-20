package com.secureleaf.auth.repository;

import com.secureleaf.auth.entity.RefreshToken;
import com.secureleaf.auth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select rt from RefreshToken rt where rt.tokenHash = :hash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("hash") String hash);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RefreshToken rt set rt.revokedAt = :now where rt.user.id = :userId and rt.revokedAt is null")
    int revokeAllForUser(@Param("userId") Long userId, @Param("now") java.time.Instant now);
}
