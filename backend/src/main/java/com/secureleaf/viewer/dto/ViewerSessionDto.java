package com.secureleaf.viewer.dto;

import java.time.OffsetDateTime;

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
        OffsetDateTime expiresAt
) {}
