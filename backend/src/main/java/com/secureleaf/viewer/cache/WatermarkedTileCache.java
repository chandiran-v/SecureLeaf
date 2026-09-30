package com.secureleaf.viewer.cache;

import java.util.Optional;

/**
 * Phase 13, D3 — Strategy interface for the server-side cache of ALREADY-WATERMARKED tiles.
 *
 * Contract every implementation must keep:
 * <ul>
 *   <li>it only ever holds watermarked bytes — the caller passes what the renderer produced;</li>
 *   <li>it performs no authorisation: {@code SecureTileService} calls it only after every check
 *       has passed (D4). A cache that answered before the checks would be an auth bypass;</li>
 *   <li>failures are the caller's to swallow: a broken cache must degrade to "miss", never to a
 *       failed tile request.</li>
 * </ul>
 */
public interface WatermarkedTileCache {

    Optional<byte[]> get(TileCacheKey key);

    void put(TileCacheKey key, byte[] watermarkedBytes);

    /** Best-effort removal of every entry belonging to the user (D5). */
    void evictUser(long userId);
}
