package com.secureleaf.auth.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Admin suspend/reactivate (Phase 8, D4) — the Redis {@code auth:suspended} set is how
 * {@link JwtAuthenticationFilter} rejects an access token minted *before* a suspension, without
 * waiting for it to expire (up to 15 minutes, {@code jwt.access-token-expiry-ms}).
 *
 * WHY A REDIS SET INSTEAD OF THE ALTERNATIVES (D4)
 * - A short access-token TTL alone still leaves a window (however small) where a suspended
 *   user's existing token keeps working — this closes it to the next request.
 * - A per-request database lookup of the user row would also catch it immediately, but costs
 *   a DB round trip on every single authenticated call just to answer a question that is true
 *   for the overwhelming majority of requests ("no, not suspended"). A Redis
 *   {@code SISMEMBER} is O(1) and answers the same question without that cost.
 * - A full deny-list keyed by JWT {@code jti} (one entry per issued token) would also work, but
 *   requires storing and expiring one Redis key per token ever issued; keying by user id
 *   instead means exactly one entry per currently-suspended user, and it self-cleans on
 *   {@link #reactivate}.
 *
 * FAIL OPEN, NOT FAIL CLOSED (D4)
 * If Redis is unreachable, {@link #isSuspended} returns {@code false} (logging an ERROR)
 * rather than rejecting every request. The alternative — fail closed — would log out every
 * user in the system during a transient Redis blip, for the sake of a check whose only job is
 * to shorten an already-bounded (≤15 minute) window. That trade is not worth it: this project's
 * usual "fail closed" instinct (e.g. TileUrlSigner rejects on any doubt) applies where the
 * check IS the security boundary; here it's a latency optimisation over a boundary
 * (account_status, enforced at login/refresh) that stays enforced regardless.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SuspendedUsersService {

    static final String SUSPENDED_SET_KEY = "auth:suspended";

    private final StringRedisTemplate redisTemplate;

    public void suspend(Long userId) {
        redisTemplate.opsForSet().add(SUSPENDED_SET_KEY, userId.toString());
    }

    public void reactivate(Long userId) {
        redisTemplate.opsForSet().remove(SUSPENDED_SET_KEY, userId.toString());
    }

    public boolean isSuspended(Long userId) {
        try {
            return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(SUSPENDED_SET_KEY, userId.toString()));
        } catch (Exception e) {
            log.error("Redis unavailable while checking auth:suspended for user id={} — failing open", userId, e);
            return false;
        }
    }
}
