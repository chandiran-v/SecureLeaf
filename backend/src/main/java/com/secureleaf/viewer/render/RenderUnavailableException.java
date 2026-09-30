package com.secureleaf.viewer.render;

/**
 * Phase 12, D4 — the render bulkhead could not produce this tile in time: either its queue was
 * full (rejected instantly) or the tile exceeded {@code render.timeout-ms}. Mapped to
 * {@code 503 Service Unavailable} + {@code Retry-After}. Deliberately NOT a BusinessException /
 * 429: nothing is wrong with the client's behaviour, the server is overloaded.
 */
public class RenderUnavailableException extends RuntimeException {

    private final long retryAfterSeconds;

    public RenderUnavailableException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
