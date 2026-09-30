package com.secureleaf.viewer.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Phase 13, D2 — everything that makes one watermarked tile different from another. If any field
 * changes, the bytes it maps to must be re-rendered, so every field is part of the hash:
 * <ul>
 *   <li>{@code userId}, {@code sessionId} — the watermark text names both (D1);</li>
 *   <li>{@code documentVersionId}, {@code pageNumber} — which clean tile;</li>
 *   <li>{@code variant} — {@code "default"} until Phase 16 adds more;</li>
 *   <li>{@code rendererId}, {@code watermarkVersion} — a renderer swap or a watermark redesign
 *       bumps one of these, which orphans every old entry at once (invalidation by key change
 *       instead of by hunting entries down).</li>
 * </ul>
 * {@code userId} is kept beside the hash so a cache can evict "everything for this user" (D5).
 */
public record TileCacheKey(long userId, long sessionId, long documentVersionId, int pageNumber,
                           String variant, String rendererId, String watermarkVersion) {

    public static final String DEFAULT_VARIANT = "default";

    /** Hex sha256 of the pipe-joined fields: fixed length and filename-safe for the disk cache. */
    public String hash() {
        String joined = String.join("|", Long.toString(userId), Long.toString(sessionId),
                Long.toString(documentVersionId), Integer.toString(pageNumber),
                variant, rendererId, watermarkVersion);
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(joined.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }
}
