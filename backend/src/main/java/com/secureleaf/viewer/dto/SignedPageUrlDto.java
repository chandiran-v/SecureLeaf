package com.secureleaf.viewer.dto;

import java.time.OffsetDateTime;

/** GraphQL {@code SignedPageUrl} — a single-use, 30-second link to one watermarked tile (D5). */
public record SignedPageUrlDto(String url, OffsetDateTime expiresAt) {}
