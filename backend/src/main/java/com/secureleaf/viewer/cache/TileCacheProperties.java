package com.secureleaf.viewer.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code tilecache.*} settings (Phase 13, D2/D3).
 *
 * @param type              {@code disk} (default, single server), {@code redis} (scale-out) or
 *                          {@code none}
 * @param dir               disk cache directory; default {@code ${java.io.tmpdir}/tile-cache}
 * @param ttl               how long an entry lives (default 15 min)
 * @param maxBytes          disk cache total size bound (default 2 GB)
 * @param maxBytesPerEntry  Redis only: tiles larger than this are not cached (default 2 MB)
 * @param watermarkVersion  part of every key (D2); bump to invalidate every cached tile
 */
@ConfigurationProperties(prefix = "tilecache")
public record TileCacheProperties(String type, String dir, Duration ttl, Long maxBytes,
                                  Integer maxBytesPerEntry, String watermarkVersion) {

    public static final String DISK = "disk";
    public static final String REDIS = "redis";
    public static final String NONE = "none";

    public TileCacheProperties {
        if (type == null || type.isBlank()) {
            type = DISK;
        }
        if (dir == null || dir.isBlank()) {
            dir = System.getProperty("java.io.tmpdir") + "/tile-cache";
        }
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            ttl = Duration.ofMinutes(15);
        }
        if (maxBytes == null || maxBytes < 1) {
            maxBytes = 2L * 1024 * 1024 * 1024;
        }
        if (maxBytesPerEntry == null || maxBytesPerEntry < 1) {
            maxBytesPerEntry = 2 * 1024 * 1024;
        }
        if (watermarkVersion == null || watermarkVersion.isBlank()) {
            watermarkVersion = "1";
        }
    }
}
