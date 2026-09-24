package com.secureleaf.common.config;

import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration that wires the MinIO SDK client.
 *
 * The MinioClient is a thread-safe, reusable connection pool to MinIO.
 * Declaring it as a @Bean means Spring creates one instance at startup
 * and injects it wherever MinioStorageService needs it.
 */
@Configuration
@RequiredArgsConstructor
public class MinioConfig {

    private final MinioProperties properties;

    @Bean
    public MinioClient minioClient() {
        return MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
    }
}
