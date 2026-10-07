package com.secureleaf.creator.service;

import com.secureleaf.common.exception.ResourceNotFoundException;
import com.secureleaf.common.storage.StorageService;
import com.secureleaf.content.entity.ContentPage;
import com.secureleaf.content.entity.DocumentVersion;
import com.secureleaf.content.repository.ContentPageRepository;
import com.secureleaf.content.tiles.TileVariantProperties;
import com.secureleaf.marketplace.entity.Product;
import com.secureleaf.marketplace.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owner preview: a creator reads their OWN product's pages, clean (no watermark).
 *
 * Why this is a separate service and not a flag on {@code SecureTileService}: that class's whole
 * job is the invariant "every byte returned has been watermarked", and it is the code path every
 * buyer hits. Adding an "unless owner" branch there would put a watermark bypass inside the most
 * sensitive class in the system. Here the bypass is the entire, tiny, owner-checked purpose.
 *
 * A watermark exists to trace a leak back to a buyer. The creator already holds the original PDF
 * (they uploaded it), so watermarking their own copy protects nothing.
 *
 * Every failure is a 404, never a 403 — same rule as {@code PreviewService}: a 403 for
 * "someone else's product" would confirm to a probing creator that the id exists.
 */
@Service
@RequiredArgsConstructor
public class CreatorPageService {

    private final ProductRepository productRepository;
    private final ContentPageRepository contentPageRepository;
    private final StorageService storageService;

    @Transactional(readOnly = true)
    public byte[] getOwnPage(Long creatorId, Long productId, int pageNumber) {
        Product product = productRepository.findByIdAndDeletedAtIsNull(productId)
                .filter(p -> p.getCreator().getId().equals(creatorId))
                .orElseThrow(() -> new ResourceNotFoundException("Product", productId));

        // Phase 15, D1 — the explicit "current" pointer: a v2 still processing is not what the
        // creator sees yet, exactly as it isn't what a new buyer would get.
        DocumentVersion version = product.getCurrentDocumentVersion();
        Integer pageCount = version != null ? version.getPageCount() : null;
        if (pageCount == null || pageNumber < 1 || pageNumber > pageCount) {
            throw new ResourceNotFoundException("CreatorPage", "pageNumber", pageNumber);
        }

        ContentPage page = contentPageRepository
                .findByDocumentVersionIdAndPageNumberAndVariant(version.getId(), pageNumber, TileVariantProperties.DESKTOP)
                .orElseThrow(() -> new ResourceNotFoundException("CreatorPage", "pageNumber", pageNumber));
        return storageService.get(page.getBucketName(), page.getMinioObjectKey());
    }
}
