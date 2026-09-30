package com.secureleaf.viewer.cache;

import java.util.Optional;

/** Phase 13, D3 — {@code tilecache.type=none}: every lookup misses, so behaviour is Phase 12's. */
public class NoopTileCache implements WatermarkedTileCache {

    @Override
    public Optional<byte[]> get(TileCacheKey key) {
        return Optional.empty();
    }

    @Override
    public void put(TileCacheKey key, byte[] watermarkedBytes) {
        // intentionally nothing
    }

    @Override
    public void evictUser(long userId) {
        // intentionally nothing
    }
}
