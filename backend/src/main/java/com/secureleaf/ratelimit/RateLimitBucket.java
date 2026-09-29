package com.secureleaf.ratelimit;

/** The named buckets of D2. {@code keyPrefix} is the first part of the Redis key ({@code tile:42}). */
public enum RateLimitBucket {
    TILE("tile", true),
    PAGE_URL("pageurl", true),
    PREVIEW("preview", false);

    private final String keyPrefix;
    private final boolean keyedByUser;

    RateLimitBucket(String keyPrefix, boolean keyedByUser) {
        this.keyPrefix = keyPrefix;
        this.keyedByUser = keyedByUser;
    }

    public String keyPrefix() {
        return keyPrefix;
    }

    /** True when the bucket key is a user id (feeds the scraper signal), false when it is an IP. */
    public boolean keyedByUser() {
        return keyedByUser;
    }
}
