package com.secureleaf.ratelimit;

import java.time.OffsetDateTime;

/** GraphQL {@code SuspectedScraper} (Phase 11, D6). User id only — an admin looks the user up. */
public record SuspectedScraperDto(Long userId, int tileCount, double tilesPerMinute, OffsetDateTime lastTileAt) {
}
