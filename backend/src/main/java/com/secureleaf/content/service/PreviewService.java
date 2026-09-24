package com.secureleaf.content.service;

import com.secureleaf.common.exception.ResourceNotFoundException;
import com.secureleaf.common.storage.StorageService;
import com.secureleaf.content.entity.ContentPage;
import com.secureleaf.content.entity.DocumentVersion;
import com.secureleaf.content.repository.ContentPageRepository;
import com.secureleaf.content.repository.DocumentVersionRepository;
import com.secureleaf.content.watermark.WatermarkRenderer;
import com.secureleaf.marketplace.entity.Product;
import com.secureleaf.marketplace.entity.ProductStatus;
import com.secureleaf.marketplace.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serves the free, watermarked preview pages for LIVE products (D5).
 *
 * THE CORE RULE THIS SERVICE EXISTS TO ENFORCE: clean page tiles must never reach the
 * browser. Two things make that true:
 *   1. No presigned URLs for the tiles bucket — ever. Presigning a clean tile IS the
 *      leak (contrast with ProductFieldResolver, which presigns thumbnails because
 *      those are already public marketing assets — page tiles are not).
 *   2. Every byte returned by {@link #getPreviewPage} has gone through
 *      {@link WatermarkRenderer} first. There is no code path in this class that
 *      returns storage bytes unmodified.
 *
 * Validation order (checked in {@link #getPreviewPage}), and why it's a 404 not a 403
 * at every step: returning 403 for "this page is beyond the free preview range" would
 * let a caller distinguish "product exists but preview is gated" from "doesn't exist",
 * which is exactly the kind of existence leak D4 avoids for the marketplace queries.
 * Page-range enforcement is the single most important test in this phase — see
 * PreviewControllerIT.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PreviewService {

    private static final String PREVIEW_LABEL = "PREVIEW · SecureLeaf";

    private final ProductRepository productRepository;
    private final DocumentVersionRepository documentVersionRepository;
    private final ContentPageRepository contentPageRepository;
    private final StorageService storageService;
    private final WatermarkRenderer watermarkRenderer;

    @Transactional(readOnly = true)
    public byte[] getPreviewPage(Long productId, int pageNumber) {
        Product product = productRepository.findByIdAndStatusAndDeletedAtIsNull(productId, ProductStatus.LIVE)
                .orElseThrow(() -> new ResourceNotFoundException("Product", productId));

        DocumentVersion documentVersion = documentVersionRepository
                .findFirstByProductIdOrderByVersionNumberDesc(productId)
                .orElseThrow(() -> new ResourceNotFoundException("DocumentVersion", "productId", productId));

        int previewLimit = Math.min(
                product.getFreePreviewPages(),
                documentVersion.getPageCount() != null ? documentVersion.getPageCount() : 0);

        if (pageNumber < 1 || pageNumber > previewLimit) {
            throw new ResourceNotFoundException("PreviewPage", "pageNumber", pageNumber);
        }

        ContentPage contentPage = contentPageRepository
                .findByDocumentVersionIdAndPageNumber(documentVersion.getId(), pageNumber)
                .orElseThrow(() -> new ResourceNotFoundException("ContentPage", "pageNumber", pageNumber));

        byte[] cleanBytes = storageService.get(contentPage.getBucketName(), contentPage.getMinioObjectKey());
        return watermarkRenderer.applyWatermark(cleanBytes, PREVIEW_LABEL);
    }
}
