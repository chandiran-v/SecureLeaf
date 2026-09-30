package com.secureleaf.viewer.cache;

import com.secureleaf.viewer.metrics.ViewerMetrics;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Phase 13, D3 — the cache for a horizontally scaled deployment: several backend instances share
 * one Redis, so a tile rendered by instance A is a hit on instance B.
 *
 * Values are the raw PNG bytes with {@code EX ttl}. A per-user Redis SET ({@code tilecache:idx:{userId}})
 * lists that user's entry hashes so {@link #evictUser} is O(entries of that user) instead of a
 * keyspace SCAN. Entries above {@code maxBytesPerEntry} are not stored: one huge tile must not
 * evict thousands of normal ones. Total size is Redis's job ({@code maxmemory} + LRU policy).
 */
public class RedisTileCache implements WatermarkedTileCache {

    private static final String ENTRY_PREFIX = "tilecache:v:";
    private static final String INDEX_PREFIX = "tilecache:idx:";

    private final RedisTemplate<String, byte[]> redis;
    private final Duration ttl;
    private final int maxBytesPerEntry;
    private final ViewerMetrics metrics;

    public RedisTileCache(RedisTemplate<String, byte[]> redis, Duration ttl, int maxBytesPerEntry,
                          ViewerMetrics metrics) {
        this.redis = redis;
        this.ttl = ttl;
        this.maxBytesPerEntry = maxBytesPerEntry;
        this.metrics = metrics;
    }

    @Override
    public Optional<byte[]> get(TileCacheKey key) {
        return Optional.ofNullable(redis.opsForValue().get(ENTRY_PREFIX + key.hash()));
    }

    @Override
    public void put(TileCacheKey key, byte[] watermarkedBytes) {
        if (watermarkedBytes.length > maxBytesPerEntry) {
            return;
        }
        String hash = key.hash();
        redis.opsForValue().set(ENTRY_PREFIX + hash, watermarkedBytes, ttl);
        // The index set lives a little longer than any entry it lists, refreshed on every put.
        String indexKey = INDEX_PREFIX + key.userId();
        redis.opsForSet().add(indexKey, hash.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        redis.expire(indexKey, ttl.plusMinutes(1));
    }

    @Override
    public void evictUser(long userId) {
        String indexKey = INDEX_PREFIX + userId;
        Set<byte[]> members = redis.opsForSet().members(indexKey);
        if (members != null && !members.isEmpty()) {
            List<String> keys = members.stream()
                    .map(m -> ENTRY_PREFIX + new String(m, java.nio.charset.StandardCharsets.UTF_8))
                    .toList();
            Long deleted = redis.delete(keys);
            metrics.recordTileCacheEviction("explicit", deleted == null ? 0 : deleted);
        }
        redis.delete(indexKey);
    }
}
