package com.secureleaf.ratelimit;

/**
 * Outcome of one token-bucket check.
 *
 * @param allowed           a token was taken
 * @param remainingTokens   tokens left after this request (X-RateLimit-Remaining)
 * @param retryAfterSeconds when rejected: whole seconds until a token exists again, rounded UP so a
 *                          client that waits exactly this long is never rejected a second time; 0 if allowed
 */
public record RateLimitDecision(boolean allowed, long remainingTokens, long retryAfterSeconds) {

    public static RateLimitDecision allow(long remaining) {
        return new RateLimitDecision(true, remaining, 0);
    }

    /** Used on Redis failure (D5, fail open): allowed, and no honest remaining figure exists. */
    public static RateLimitDecision failOpen() {
        return new RateLimitDecision(true, -1, 0);
    }

    public static RateLimitDecision reject(long retryAfterSeconds) {
        return new RateLimitDecision(false, 0, retryAfterSeconds);
    }
}
