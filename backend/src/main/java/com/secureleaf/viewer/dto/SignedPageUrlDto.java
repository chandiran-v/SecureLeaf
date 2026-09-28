package com.secureleaf.viewer.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * GraphQL {@code SignedPageUrl} — a single-use, 30-second link to one watermarked tile (D5),
 * plus the page's clickable links (V8), which the reader overlays on the canvas.
 */
public record SignedPageUrlDto(String url, OffsetDateTime expiresAt, List<PageLinkDto> links) {}
