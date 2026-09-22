package com.secureleaf.marketplace.resolver;

import com.secureleaf.common.config.MinioProperties;
import com.secureleaf.common.storage.StorageService;
import com.secureleaf.marketplace.dto.ProductDto;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.time.Duration;

/**
 * Resolves {@code Product.thumbnailUrl} lazily, only when a client actually selects it.
 *
 * WHY A FIELD RESOLVER INSTEAD OF DOING THIS IN ProductMapper (D3)
 * ProductMapper is a static, dependency-free utility by design (its javadoc documents
 * why: it makes the "must be called inside a transaction" contract visible at the call
 * site). Presigning requires StorageService, a Spring bean — injecting that into a
 * static mapper would break that contract. A @SchemaMapping field resolver is Spring for
 * GraphQL's mechanism for "compute this field only if selected, with DI available."
 *
 * ProductDto.thumbnailKey holds the raw MinIO object key (never sent to a client as-is —
 * see ProductDto's javadoc). This resolver turns it into a time-limited, signed URL.
 *
 * TTL is 1 hour — contrast with the 30-second TTL Phase 5 uses for DRM page tiles
 * (see application.yml `drm.signed-url-ttl-seconds`). Thumbnails are public marketing
 * assets (like a book cover), not protected content, so a much longer TTL is fine and
 * reduces how often the browser has to re-request a fresh URL.
 *
 * FRONTEND CAVEAT: because the URL differs per request (a fresh signature each time),
 * never use it as a React `key` — use `product.id` instead.
 */
@Controller
@RequiredArgsConstructor
public class ProductFieldResolver {

    private static final Duration THUMBNAIL_URL_TTL = Duration.ofHours(1);

    private final StorageService storageService;
    private final MinioProperties minioProperties;

    @SchemaMapping(typeName = "Product", field = "thumbnailUrl")
    public String thumbnailUrl(ProductDto product) {
        if (product.thumbnailKey() == null || product.thumbnailKey().isBlank()) {
            return null;
        }
        return storageService.presignedGetUrl(
                minioProperties.getBucket().getThumbnails(), product.thumbnailKey(), THUMBNAIL_URL_TTL);
    }
}
