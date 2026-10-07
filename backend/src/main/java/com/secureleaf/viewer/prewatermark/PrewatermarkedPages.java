package com.secureleaf.viewer.prewatermark;

import com.secureleaf.common.storage.StorageService;
import com.secureleaf.viewer.metrics.ViewerMetrics;
import com.secureleaf.viewer.repository.BuyerPageRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The view path's side of purchase-time watermarking: "does this buyer already have this page,
 * watermarked and stored?". Called by {@code SecureTileService} only AFTER every access check
 * (signature, single use, session, ACTIVE entitlement, page range) has passed. A stored page skips
 * the clean-tile fetch AND the render; it does not skip the access log.
 *
 * <p>Never fails a request: no row (not generated yet) or a storage error is just a miss, and the
 * caller watermarks the page live exactly as before this existed.
 */
@Component
@Slf4j
public class PrewatermarkedPages {

    private final BuyerPageRepository buyerPageRepository;
    private final StorageService storageService;
    private final ViewerMetrics metrics;
    private final String watermarkVersion;

    public PrewatermarkedPages(BuyerPageRepository buyerPageRepository,
                               StorageService storageService,
                               ViewerMetrics metrics,
                               @Value("${drm.prewatermark.version:1}") String watermarkVersion) {
        this.buyerPageRepository = buyerPageRepository;
        this.storageService = storageService;
        this.metrics = metrics;
        this.watermarkVersion = watermarkVersion;
    }

    public Optional<byte[]> find(Long entitlementId, Long documentVersionId, int pageNumber, String variant) {
        var row = buyerPageRepository.findByEntitlementIdAndDocumentVersionIdAndPageNumberAndVariantAndWatermarkVersion(
                entitlementId, documentVersionId, pageNumber, variant, watermarkVersion);
        if (row.isEmpty()) {
            metrics.recordPrewatermarkMiss();
            return Optional.empty();
        }
        try {
            byte[] bytes = metrics.timeStorageFetch(
                    () -> storageService.get(row.get().getBucketName(), row.get().getObjectKey()));
            metrics.recordPrewatermarkHit();
            return Optional.of(bytes);
        } catch (RuntimeException e) {
            log.warn("Pre-watermarked page unreadable ({}); watermarking live instead: {}",
                    row.get().getObjectKey(), e.getMessage());
            metrics.recordPrewatermarkMiss();
            return Optional.empty();
        }
    }
}
