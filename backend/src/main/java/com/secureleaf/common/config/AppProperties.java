package com.secureleaf.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.*} settings — currently just the frontend's public base URL (D10), used to build
 * links that get emailed out (e.g. the password reset link) rather than posted back over the API.
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(String frontendBaseUrl) {
}
