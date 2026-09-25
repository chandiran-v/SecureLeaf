package com.secureleaf.viewer;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code drm.*} settings for the secure viewer (D11).
 *
 * @param signingSecret        HMAC key for {@link com.secureleaf.viewer.security.TileUrlSigner}.
 *                             Never sent to the browser; a leak lets anyone mint their own tile URLs.
 * @param signedUrlTtlSeconds  how long a signed tile URL stays valid (D5) — 30s in dev/prod.
 * @param session              heartbeat/lease timings (D4).
 */
@ConfigurationProperties(prefix = "drm")
public record DrmProperties(String signingSecret, int signedUrlTtlSeconds, Session session) {

    public record Session(int heartbeatIntervalSeconds, int leaseSeconds) {
    }
}
