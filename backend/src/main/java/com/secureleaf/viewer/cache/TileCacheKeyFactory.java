package com.secureleaf.viewer.cache;

/** Phase 13, D2 — fills in the two config-derived key parts so callers only pass request facts. */
public class TileCacheKeyFactory {

    private final String rendererId;
    private final String watermarkVersion;

    public TileCacheKeyFactory(String rendererId, String watermarkVersion) {
        this.rendererId = rendererId;
        this.watermarkVersion = watermarkVersion;
    }

    public TileCacheKey keyFor(long userId, long sessionId, long documentVersionId, int pageNumber) {
        return new TileCacheKey(userId, sessionId, documentVersionId, pageNumber,
                TileCacheKey.DEFAULT_VARIANT, rendererId, watermarkVersion);
    }
}
