package com.secureleaf.ratelimit;

import io.github.bucket4j.TimeMeter;
import io.lettuce.core.RedisClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 11 acceptance criteria 1, 2, 3 and 6 against a REAL Redis — the Bucket4j CAS script is the
 * thing under test, so a fake would prove nothing. No Spring context: the limiter is a plain
 * object, and the clock is a hand-cranked {@link TimeMeter} so "after waiting" needs no sleep.
 */
class RateLimiterTest {

    private static final RateLimitProperties PROPS = new RateLimitProperties(
            new RateLimitProperties.Limit(5, 2),
            new RateLimitProperties.Limit(10, 4),
            new RateLimitProperties.Limit(20, 1),
            java.util.List.of(),
            new RateLimitProperties.Scraper(100, 10, 30));

    private static GenericContainer<?> redis;
    private static RedisClient client;

    /** A clock the test winds forward by hand. */
    private static final class ManualClock implements TimeMeter {
        private final AtomicLong nanos = new AtomicLong(System.currentTimeMillis() * 1_000_000L);

        void advanceMillis(long ms) {
            nanos.addAndGet(ms * 1_000_000L);
        }

        @Override
        public long currentTimeNanos() {
            return nanos.get();
        }

        @Override
        public boolean isWallClockBased() {
            return true;
        }
    }

    @BeforeAll
    static void startRedis() {
        redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
        redis.start();
        client = RedisClient.create("redis://" + redis.getHost() + ":" + redis.getMappedPort(6379));
    }

    @AfterAll
    static void stopRedis() {
        client.shutdown();
        redis.stop();
    }

    private static RateLimiter limiter(RedisClient redisClient, TimeMeter clock, SimpleMeterRegistry registry) {
        return new RateLimiter(() -> RateLimitConfig.proxyManager(redisClient, clock), PROPS, registry, RejectionListener.NONE);
    }

    private static String user() {
        return UUID.randomUUID().toString();
    }

    @Test
    void sixthImmediateRequestIsRejectedWithRetryAfter_thenSucceedsAfterTimePasses() {
        ManualClock clock = new ManualClock();
        RateLimiter limiter = limiter(client, clock, new SimpleMeterRegistry());
        String user = user();

        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryConsume(RateLimitBucket.TILE, user).allowed()).as("request %d", i + 1).isTrue();
        }
        RateLimitDecision sixth = limiter.tryConsume(RateLimitBucket.TILE, user);
        assertThat(sixth.allowed()).isFalse();
        assertThat(sixth.retryAfterSeconds()).isEqualTo(1);   // 2 tokens/s => next token within 0.5s, rounded UP

        clock.advanceMillis(500);   // one token has refilled
        assertThat(limiter.tryConsume(RateLimitBucket.TILE, user).allowed()).isTrue();
        assertThat(limiter.tryConsume(RateLimitBucket.TILE, user).allowed()).isFalse();

        clock.advanceMillis(60_000);   // long idle: back to a full bucket of 5, never more
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryConsume(RateLimitBucket.TILE, user).allowed()).isTrue();
        }
        assertThat(limiter.tryConsume(RateLimitBucket.TILE, user).allowed()).isFalse();
    }

    @Test
    void limitsArePerKey_oneThrottledUserDoesNotAffectAnother() {
        RateLimiter limiter = limiter(client, new ManualClock(), new SimpleMeterRegistry());
        String a = user();
        String b = user();
        for (int i = 0; i < 5; i++) {
            limiter.tryConsume(RateLimitBucket.TILE, a);
        }
        assertThat(limiter.tryConsume(RateLimitBucket.TILE, a).allowed()).isFalse();
        assertThat(limiter.tryConsume(RateLimitBucket.TILE, b).allowed()).isTrue();
        // ...and buckets of different kinds are independent for the same key.
        assertThat(limiter.tryConsume(RateLimitBucket.PAGE_URL, a).allowed()).isTrue();
    }

    @Test
    void bucketsAreSharedAcrossTwoAppInstancesUsingTheSameRedis() {
        ManualClock clock = new ManualClock();
        // Two independent limiters with their own Redis connection = two app instances.
        RedisClient otherClient = RedisClient.create("redis://" + redis.getHost() + ":" + redis.getMappedPort(6379));
        try {
            RateLimiter instanceA = limiter(client, clock, new SimpleMeterRegistry());
            RateLimiter instanceB = limiter(otherClient, clock, new SimpleMeterRegistry());
            String user = user();

            for (int i = 0; i < 3; i++) {
                assertThat(instanceA.tryConsume(RateLimitBucket.TILE, user).allowed()).isTrue();
            }
            for (int i = 0; i < 2; i++) {
                assertThat(instanceB.tryConsume(RateLimitBucket.TILE, user).allowed()).isTrue();
            }
            // 5 tokens spent across both instances: neither has any left.
            assertThat(instanceA.tryConsume(RateLimitBucket.TILE, user).allowed()).isFalse();
            assertThat(instanceB.tryConsume(RateLimitBucket.TILE, user).allowed()).isFalse();
        } finally {
            otherClient.shutdown();
        }
    }

    @Test
    void concurrentRequestsCannotOverspendTheBucket() throws Exception {
        RateLimiter limiter = limiter(client, new ManualClock(), new SimpleMeterRegistry());
        String user = user();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(16);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 40; i++) {
                futures.add(pool.submit(() -> limiter.tryConsume(RateLimitBucket.TILE, user).allowed()));
            }
            long allowed = 0;
            for (var f : futures) {
                if (f.get()) {
                    allowed++;
                }
            }
            assertThat(allowed).isEqualTo(5);   // the CAS makes check-and-take atomic: exactly the capacity
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void redisDown_requestIsAllowedAndErrorMetricIncrements() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RedisClient dead = RedisClient.create("redis://127.0.0.1:1");   // nothing listens on port 1
        try {
            RateLimiter limiter = limiter(dead, new ManualClock(), registry);

            RateLimitDecision decision = limiter.tryConsume(RateLimitBucket.TILE, "1");

            assertThat(decision.allowed()).isTrue();
            assertThat(registry.get(RateLimiter.REDIS_ERRORS).counter().count()).isEqualTo(1.0);

            // Within the back-off window Redis is not even tried again: still allowed, no new error.
            assertThat(limiter.tryConsume(RateLimitBucket.TILE, "1").allowed()).isTrue();
            assertThat(registry.get(RateLimiter.REDIS_ERRORS).counter().count()).isEqualTo(1.0);
        } finally {
            dead.shutdown();
        }
    }

    @Test
    void retryAfterIsRoundedUpAndNeverZero() {
        assertThat(RateLimiter.secondsRoundedUp(1)).isEqualTo(1);
        assertThat(RateLimiter.secondsRoundedUp(1_000_000_000L)).isEqualTo(1);
        assertThat(RateLimiter.secondsRoundedUp(1_000_000_001L)).isEqualTo(2);
        assertThat(RateLimiter.secondsRoundedUp(0)).isEqualTo(1);
    }
}
