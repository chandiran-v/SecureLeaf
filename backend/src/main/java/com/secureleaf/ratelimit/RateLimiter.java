package com.secureleaf.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Phase 11 — the token-bucket limiter (D1). One bucket per (bucket kind, key) pair, e.g.
 * {@code tile:42}; its state (tokens left + last refill time) lives in Redis, so every app
 * instance draws from the same bucket and a scraper cannot multiply its budget by hitting
 * different instances.
 *
 * WHY A TOKEN BUCKET
 * It allows a small burst (a reader prefetching the next page) but caps the sustained rate — the
 * exact shape of honest page-turning. See the learning note for fixed window / sliding log / leaky bucket.
 *
 * ATOMICITY
 * Two requests racing for the last token must not both win. Bucket4j's Lettuce proxy manager does
 * a compare-and-swap on the bucket's Redis value (a Lua script) and retries on conflict, so the
 * read-modify-write is atomic without any lock in this class.
 *
 * FAIL OPEN (D5)
 * If Redis is unreachable the request is allowed, a WARN is logged and
 * {@value #REDIS_ERRORS} increments. Rate limiting is protection, not correctness: an outage of a
 * cache must not turn into an outage of reading books a buyer paid for.
 */
@Slf4j
public class RateLimiter {

    public static final String REJECTED = "secureleaf.ratelimit.rejected";
    public static final String REDIS_ERRORS = "secureleaf.ratelimit.redis_errors";

    /** After a Redis failure, skip Redis entirely for this long so a dead Redis costs one timeout, not one per request. */
    private static final long BACKOFF_NANOS = Duration.ofSeconds(5).toNanos();

    private final Supplier<ProxyManager<byte[]>> proxyManagerSupplier;
    private final Map<RateLimitBucket, BucketConfiguration> configurations = new EnumMap<>(RateLimitBucket.class);
    private final MeterRegistry registry;
    private final RejectionListener rejectionListener;
    private final Counter redisErrors;

    private volatile ProxyManager<byte[]> proxyManager;
    private volatile long redisDownUntilNanos;

    public RateLimiter(Supplier<ProxyManager<byte[]>> proxyManagerSupplier, RateLimitProperties properties,
                       MeterRegistry registry, RejectionListener rejectionListener) {
        this.proxyManagerSupplier = proxyManagerSupplier;
        this.registry = registry;
        this.rejectionListener = rejectionListener;
        configurations.put(RateLimitBucket.TILE, configuration(properties.tile()));
        configurations.put(RateLimitBucket.PAGE_URL, configuration(properties.pageUrl()));
        configurations.put(RateLimitBucket.PREVIEW, configuration(properties.preview()));
        this.redisErrors = Counter.builder(REDIS_ERRORS)
                .description("Rate-limit checks skipped (allowed) because Redis was unreachable")
                .register(registry);
    }

    private static BucketConfiguration configuration(RateLimitProperties.Limit limit) {
        return BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(limit.capacity())
                        .refillGreedy(limit.refillPerSecond(), Duration.ofSeconds(1))
                        .build())
                .build();
    }

    /** Takes one token, or says how long to wait. Never throws: a Redis failure means "allowed". */
    public RateLimitDecision tryConsume(RateLimitBucket bucket, String key) {
        if (System.nanoTime() - redisDownUntilNanos < 0) {
            return RateLimitDecision.failOpen();
        }
        ConsumptionProbe probe;
        try {
            probe = proxyManager()
                    .builder()
                    .build(redisKey(bucket, key), () -> configurations.get(bucket))
                    .tryConsumeAndReturnRemaining(1);
        } catch (RuntimeException e) {
            redisDownUntilNanos = System.nanoTime() + BACKOFF_NANOS;
            proxyManager = null; // a dead connection is rebuilt on the next attempt
            redisErrors.increment();
            log.warn("Rate limiter cannot reach Redis - failing OPEN for {}s: {}",
                    BACKOFF_NANOS / 1_000_000_000L, e.toString());
            return RateLimitDecision.failOpen();
        }
        if (probe.isConsumed()) {
            return RateLimitDecision.allow(probe.getRemainingTokens());
        }
        Counter.builder(REJECTED)
                .description("Requests rejected with 429 by the rate limiter")
                .tag("bucket", bucket.keyPrefix())
                .register(registry)
                .increment();
        rejectionListener.onRejected(bucket, key);
        return RateLimitDecision.reject(secondsRoundedUp(probe.getNanosToWaitForRefill()));
    }

    /** For resolvers: throws {@link RateLimitedException} instead of returning the decision. */
    public void assertAllowed(RateLimitBucket bucket, String key) {
        RateLimitDecision decision = tryConsume(bucket, key);
        if (!decision.allowed()) {
            throw new RateLimitedException(decision.retryAfterSeconds());
        }
    }

    private ProxyManager<byte[]> proxyManager() {
        ProxyManager<byte[]> current = proxyManager;
        if (current == null) {
            // Built lazily, not at startup: an unreachable Redis must not stop the app booting (D5).
            synchronized (this) {
                current = proxyManager;
                if (current == null) {
                    current = proxyManagerSupplier.get();
                    proxyManager = current;
                }
            }
        }
        return current;
    }

    private static byte[] redisKey(RateLimitBucket bucket, String key) {
        return ("ratelimit:" + bucket.keyPrefix() + ":" + key).getBytes(StandardCharsets.UTF_8);
    }

    /** Ceiling, and never 0: "retry in 0 seconds" would invite an immediate second rejection. */
    static long secondsRoundedUp(long nanos) {
        return Math.max(1L, (nanos + 999_999_999L) / 1_000_000_000L);
    }
}
