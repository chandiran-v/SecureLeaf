package com.secureleaf.viewer.cache;

import com.github.benmanes.caffeine.cache.Ticker;
import com.secureleaf.viewer.metrics.ViewerMetrics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.nio.file.Path;

/**
 * Phase 13, D3 — picks the {@link WatermarkedTileCache} implementation from {@code tilecache.type}.
 * A plain switch rather than {@code @ConditionalOnProperty} pairs: {@code disk} must win when the
 * property is absent, and an unknown value should fail at startup, not silently disable caching.
 */
@Configuration
@EnableConfigurationProperties(TileCacheProperties.class)
public class TileCacheConfig {

    @Bean
    public WatermarkedTileCache watermarkedTileCache(TileCacheProperties props,
                                                     RedisConnectionFactory redisConnectionFactory,
                                                     ViewerMetrics metrics) {
        return switch (props.type().toLowerCase()) {
            case TileCacheProperties.DISK ->
                    new DiskTileCache(Path.of(props.dir()), props.ttl(), props.maxBytes(), Ticker.systemTicker(), metrics);
            case TileCacheProperties.REDIS -> new RedisTileCache(binaryRedisTemplate(redisConnectionFactory),
                    props.ttl(), props.maxBytesPerEntry(), metrics);
            case TileCacheProperties.NONE -> new NoopTileCache();
            default -> throw new IllegalStateException(
                    "tilecache.type must be disk, redis or none, but was '" + props.type() + "'");
        };
    }

    private static RedisTemplate<String, byte[]> binaryRedisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, byte[]> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(StringRedisSerializer.UTF_8);
        template.setValueSerializer(RedisSerializer.byteArray());
        template.afterPropertiesSet();
        return template;
    }

    /** Builds the D2 key; the renderer id is the configured {@code drm.watermark.renderer}. */
    @Bean
    public TileCacheKeyFactory tileCacheKeyFactory(TileCacheProperties props,
                                                   @Value("${drm.watermark.renderer:java2d}") String rendererId) {
        return new TileCacheKeyFactory(rendererId, props.watermarkVersion());
    }
}
