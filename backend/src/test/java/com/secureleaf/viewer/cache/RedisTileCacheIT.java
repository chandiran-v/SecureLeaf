package com.secureleaf.viewer.cache;

import com.secureleaf.AbstractIntegrationTest;
import com.secureleaf.viewer.metrics.ViewerMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 13, D3 — {@link RedisTileCache} against a real Redis (binary values, TTL, per-user eviction). */
class RedisTileCacheIT extends AbstractIntegrationTest {

    @Autowired private RedisConnectionFactory factory;
    @Autowired private ViewerMetrics metrics;

    private RedisTileCache cache(int maxBytesPerEntry) {
        RedisTemplate<String, byte[]> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(StringRedisSerializer.UTF_8);
        template.setValueSerializer(RedisSerializer.byteArray());
        template.afterPropertiesSet();
        return new RedisTileCache(template, Duration.ofSeconds(900), maxBytesPerEntry, metrics);
    }

    private static TileCacheKey key(long user, int page) {
        return new TileCacheKey(user, 1, 1, page, TileCacheKey.DEFAULT_VARIANT, "java2d", "1");
    }

    @Test
    void roundTripsBinaryBytes_withTtl() {
        RedisTileCache cache = cache(1024);
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0, (byte) 0xFF, (byte) 0xFE};
        cache.put(key(1, 1), png);

        assertThat(cache.get(key(1, 1))).hasValue(png);
        assertThat(cache.get(key(1, 2))).isEmpty();
        Long ttl = new org.springframework.data.redis.core.StringRedisTemplate(factory)
                .getExpire("tilecache:v:" + key(1, 1).hash());
        assertThat(ttl).isBetween(1L, 900L);
    }

    @Test
    void oversizedEntry_isNotCached() {
        RedisTileCache cache = cache(4);
        cache.put(key(1, 1), new byte[10]);
        assertThat(cache.get(key(1, 1))).isEmpty();
    }

    @Test
    void evictUser_removesOnlyThatUsersEntries() {
        RedisTileCache cache = cache(1024);
        cache.put(key(1, 1), new byte[]{1});
        cache.put(key(1, 2), new byte[]{2});
        cache.put(key(2, 1), new byte[]{3});

        cache.evictUser(1);

        assertThat(cache.get(key(1, 1))).isEmpty();
        assertThat(cache.get(key(1, 2))).isEmpty();
        assertThat(cache.get(key(2, 1))).isPresent();
    }
}
