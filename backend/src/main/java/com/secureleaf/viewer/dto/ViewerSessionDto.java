package com.secureleaf.viewer.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * GraphQL {@code ViewerSession} — returned once, at {@code startViewerSession}. {@code sessionToken}
 * is the only time the raw (unhashed) token ever leaves the server; only its SHA-256 hash is
 * stored (D2), so losing this response means losing the session, same as a refresh token.
 */
public record ViewerSessionDto(
        Long sessionId,
        String sessionToken,
        Long productId,
        Integer pageCount,
        Integer heartbeatIntervalSeconds,
        List<TileVariantDto> tileVariants,
        OffsetDateTime expiresAt
) {

    /** Phase 16, D5 — a resolution the viewer may ask for, and how wide its images are. */
    public record TileVariantDto(String name, int widthPx) {}
}
