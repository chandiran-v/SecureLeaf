package com.secureleaf.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * {@code ratelimit.*} settings (Phase 11, D2) — every number lives in configuration, none is
 * hard-coded, so an operator can tighten a limit under attack without a redeploy.
 *
 * @param tile           per-buyer bucket for the tile endpoint (MVP2-04: 2 req/s sustained + burst)
 * @param pageUrl        per-buyer bucket for the {@code viewerPageUrl} GraphQL query
 * @param preview        per-client-IP bucket for the public free-preview endpoint
 * @param trustedProxies CIDRs whose {@code X-Forwarded-For} we believe (D4); empty = never trust it
 * @param scraper        thresholds for the abuse signal (D6)
 */
@ConfigurationProperties(prefix = "ratelimit")
public record RateLimitProperties(Limit tile, Limit pageUrl, Limit preview,
                                  List<String> trustedProxies, Scraper scraper) {

    /** Token bucket shape: holds at most {@code capacity} tokens, gains {@code refillPerSecond} per second. */
    public record Limit(int capacity, int refillPerSecond) {
    }

    /**
     * @param rejectionsThreshold  more rejections than this inside the window logs a WARN
     * @param rejectionWindowMinutes length of the rejection-counting window
     * @param suspectTilesPerMinute {@code suspectedScrapers} flags a user whose *successful* tile
     *                              rate (from viewer_access_logs) is above this average
     */
    public record Scraper(int rejectionsThreshold, int rejectionWindowMinutes, int suspectTilesPerMinute) {
    }
}
