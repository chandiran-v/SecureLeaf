package com.secureleaf.ratelimit;

/** Told about every rejection, so the abuse signal (D6) can be built without RateLimiter knowing about it. */
@FunctionalInterface
public interface RejectionListener {

    void onRejected(RateLimitBucket bucket, String key);

    RejectionListener NONE = (bucket, key) -> { };
}
