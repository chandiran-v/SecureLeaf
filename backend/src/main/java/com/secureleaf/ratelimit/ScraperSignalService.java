package com.secureleaf.ratelimit;

import com.secureleaf.viewer.repository.ViewerAccessLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Phase 11, D6 — turning "a user keeps hitting the limit" into something an admin can act on.
 * Two independent signals, because each has a blind spot:
 * <ol>
 *   <li><b>Rejections</b> (this class as a {@link RejectionListener}): a Redis counter per user,
 *       10-minute TTL. Crossing the threshold logs {@code WARN possible scraper userId=…} once per
 *       window. Sees an attacker who is being throttled right now; it is lost if Redis is flushed.</li>
 *   <li><b>Access logs</b> ({@link #suspectedScrapers}): successful tiles per user from the durable
 *       {@code viewer_access_logs} table. Sees a slow scraper who stays just under the limit.</li>
 * </ol>
 * Only the numeric user id is logged — never an email or a token. Nothing here suspends anyone:
 * admins decide (out of scope: automatic suspension).
 */
@Service
@Slf4j
public class ScraperSignalService implements RejectionListener {

    private static final String KEY_PREFIX = "ratelimit:rejects:";

    private final StringRedisTemplate redis;
    private final ViewerAccessLogRepository accessLogs;
    private final RateLimitProperties properties;
    private final Clock clock;

    public ScraperSignalService(StringRedisTemplate redis, ViewerAccessLogRepository accessLogs,
                                RateLimitProperties properties, Clock clock) {
        this.redis = redis;
        this.accessLogs = accessLogs;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public void onRejected(RateLimitBucket bucket, String key) {
        if (!bucket.keyedByUser()) {
            return; // IP-keyed rejections say nothing about one buyer
        }
        try {
            String redisKey = KEY_PREFIX + key;
            Long count = redis.opsForValue().increment(redisKey);
            if (count != null && count == 1L) {
                redis.expire(redisKey, Duration.ofMinutes(properties.scraper().rejectionWindowMinutes()));
            }
            // == not >=: log once when the line is crossed, not on every later rejection.
            if (count != null && count == properties.scraper().rejectionsThreshold() + 1L) {
                log.warn("possible scraper userId={} ({} rate-limit rejections in {} minutes)",
                        key, count, properties.scraper().rejectionWindowMinutes());
            }
        } catch (RuntimeException e) {
            log.warn("Could not update scraper signal (Redis unavailable): {}", e.toString());
        }
    }

    /** Users whose average successful-tile rate over the last {@code windowMinutes} exceeds the threshold. */
    public List<SuspectedScraperDto> suspectedScrapers(int windowMinutes) {
        int window = Math.max(1, windowMinutes);
        long minTiles = (long) properties.scraper().suspectTilesPerMinute() * window;
        Instant since = Instant.now(clock).minus(Duration.ofMinutes(window));
        return accessLogs.findHeavyViewers(since, minTiles).stream()
                .map(row -> new SuspectedScraperDto(row.getUserId(), (int) row.getTileCount(),
                        (double) row.getTileCount() / window,
                        row.getLastTileAt().atOffset(ZoneOffset.UTC)))
                .toList();
    }
}
