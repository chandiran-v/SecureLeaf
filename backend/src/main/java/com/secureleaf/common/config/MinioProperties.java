package com.secureleaf.common.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Binds the {@code minio.*} block in application.yml to a typed bean.
 *
 * Spring auto-converts kebab-case YAML keys to camelCase:
 *   minio.access-key     → accessKey
 *   minio.secret-key     → secretKey
 *   minio.bucket.raw-uploads → bucket.rawUploads
 *
 * Follows the same @ConfigurationProperties pattern as JwtProperties.
 */
@Component
@ConfigurationProperties(prefix = "minio")
@Validated
@Data
public class MinioProperties {

    @NotBlank
    private String endpoint;

    @NotBlank
    private String accessKey;

    @NotBlank
    private String secretKey;

    private Bucket bucket = new Bucket();

    @Data
    public static class Bucket {
        @NotBlank
        private String rawUploads = "secureleaf-raw";

        @NotBlank
        private String tiles = "secureleaf-tiles";

        @NotBlank
        private String thumbnails = "secureleaf-thumbnails";
    }
}
