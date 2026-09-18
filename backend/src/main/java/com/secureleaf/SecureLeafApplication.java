package com.secureleaf;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * SecureLeaf — DRM-protected digital content marketplace.
 *
 * Entry point for the Spring Boot 3 application.
 * {@code @EnableAsync} activates the ThreadPoolTaskExecutor used by the
 * content processing pipeline (@Async methods in the upload module).
 */
@SpringBootApplication
@EnableAsync
@EnableJpaAuditing
public class SecureLeafApplication {

    public static void main(String[] args) {
        SpringApplication.run(SecureLeafApplication.class, args);
    }
}
