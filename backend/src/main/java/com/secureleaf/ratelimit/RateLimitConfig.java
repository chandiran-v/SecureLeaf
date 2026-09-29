package com.secureleaf.ratelimit;

import io.github.bucket4j.TimeMeter;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ClientSideConfig;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/** Phase 11 wiring: a dedicated Lettuce client for Bucket4j, pointed at the same Redis as the rest of the app. */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {

    private static final Duration IO_TIMEOUT = Duration.ofMillis(500);

    /**
     * A separate client from Spring Data Redis's connection factory because Bucket4j needs raw
     * byte[] codecs. Timeouts are short and disconnected commands are rejected immediately, so a
     * dead Redis makes the limiter fail open fast (D5) instead of stalling every request.
     */
    @Bean(destroyMethod = "shutdown")
    public RedisClient rateLimitRedisClient(RedisProperties redis) {
        RedisURI.Builder uri = RedisURI.builder()
                .withHost(redis.getHost())
                .withPort(redis.getPort())
                .withTimeout(IO_TIMEOUT);
        if (redis.getPassword() != null && !redis.getPassword().isEmpty()) {
            uri.withPassword(redis.getPassword().toCharArray());
        }
        RedisClient client = RedisClient.create(uri.build());
        client.setOptions(ClientOptions.builder()
                .socketOptions(SocketOptions.builder().connectTimeout(IO_TIMEOUT).build())
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .build());
        return client;
    }

    @Bean
    public RateLimiter rateLimiter(RedisClient rateLimitRedisClient, RateLimitProperties properties,
                                   MeterRegistry registry, ScraperSignalService scraperSignal) {
        return new RateLimiter(
                () -> proxyManager(rateLimitRedisClient, TimeMeter.SYSTEM_MILLISECONDS),
                properties, registry, scraperSignal);
    }

    /** Shared with the tests, which pass a controllable {@link TimeMeter} instead of the system clock. */
    public static ProxyManager<byte[]> proxyManager(RedisClient client, TimeMeter clock) {
        return LettuceBasedProxyManager.builderFor(client)
                .withClientSideConfig(ClientSideConfig.getDefault()
                        .withClientClock(clock)
                        // Idle buckets are refilled to full anyway, so Redis may forget them:
                        // no key leak from one-off IPs hitting the preview endpoint.
                        .withExpirationAfterWriteStrategy(
                                ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofSeconds(30))))
                .build();
    }
}
