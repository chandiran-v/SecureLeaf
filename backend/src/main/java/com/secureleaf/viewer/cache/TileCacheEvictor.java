package com.secureleaf.viewer.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Phase 13, D5 — the entry point other modules (refunds, suspension) call to drop a user's cached
 * tiles. Best-effort by design: a Redis hiccup here must not roll back a refund or fail a
 * suspension. That is safe because the entitlement/session checks run BEFORE any cache lookup, so
 * a leftover entry can never be served to someone who lost access; eviction only frees space and
 * shortens the time revoked buyers' bytes sit on disk.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TileCacheEvictor {

    private final WatermarkedTileCache tileCache;

    public void evictUser(Long userId) {
        try {
            tileCache.evictUser(userId);
        } catch (RuntimeException e) {
            log.warn("Could not evict tile cache entries for user id={}; the TTL will clear them", userId, e);
        }
    }
}
