package com.secureleaf.viewer.render;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Phase 12 — registers {@link RenderProperties}. */
@Configuration
@EnableConfigurationProperties(RenderProperties.class)
public class RenderConfig {
}
