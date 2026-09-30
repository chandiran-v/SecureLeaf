package com.secureleaf.ratelimit;

import com.secureleaf.common.exception.BusinessException;
import com.secureleaf.common.exception.ErrorCode;

/**
 * Thrown by {@link RateLimiter#assertAllowed} from GraphQL resolvers. A {@link BusinessException}
 * subtype so it keeps working anywhere that already handles RATE_LIMITED, but it also carries
 * {@code retryAfterSeconds}, which the handlers surface as a {@code Retry-After} header (REST) or
 * an {@code extensions.retryAfterSeconds} field (GraphQL) — D3.
 */
public class RateLimitedException extends BusinessException {

    private final long retryAfterSeconds;

    public RateLimitedException(long retryAfterSeconds) {
        super(ErrorCode.RATE_LIMITED, "Too many requests. Please slow down.");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
