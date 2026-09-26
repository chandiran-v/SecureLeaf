package com.secureleaf.common.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers {@link AppProperties} — same pattern as CommerceConfig/DrmConfig. */
@Configuration
@EnableConfigurationProperties(AppProperties.class)
public class AppConfig {
}
