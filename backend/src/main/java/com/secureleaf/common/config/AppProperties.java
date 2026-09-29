package com.secureleaf.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * {@code app.*} settings.
 *
 * @param frontendBaseUrl     used to build links that get emailed out (e.g. the password reset
 *                            link) rather than posted back over the API (Phase 7, D10)
 * @param corsAllowedOrigins  comma-separated list of origins the browser is allowed to call this
 *                            API from (Phase 9, D3/D9). Local dev's two Vite/CRA ports are the
 *                            default; a real deployment sets {@code CORS_ALLOWED_ORIGINS} to its
 *                            actual Vercel URL(s) — see docs/deployment.md. Frontend and backend
 *                            are same-origin behind the Docker Compose nginx config, which never
 *                            triggers CORS at all; this only matters for the Vercel+Render split
 *                            where they're genuinely different origins.
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(String frontendBaseUrl, String corsAllowedOrigins) {

    public List<String> corsAllowedOriginsList() {
        return corsAllowedOrigins == null || corsAllowedOrigins.isBlank()
                ? List.of()
                : List.of(corsAllowedOrigins.split(","));
    }
}
