package com.secureleaf.creator.service;

import com.secureleaf.common.exception.BusinessException;
import com.secureleaf.common.exception.ErrorCode;
import com.secureleaf.common.exception.ResourceNotFoundException;
import com.secureleaf.content.entity.DocumentVersion;
import com.secureleaf.content.repository.DocumentVersionRepository;
import com.secureleaf.creator.entity.JobStatus;
import com.secureleaf.creator.entity.ProcessingJob;
import com.secureleaf.creator.repository.ProcessingJobRepository;
import com.secureleaf.marketplace.dto.ProductDto;
import com.secureleaf.marketplace.entity.Product;
import com.secureleaf.marketplace.entity.ProductStatus;
import com.secureleaf.marketplace.mapper.ProductMapper;
import com.secureleaf.marketplace.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DASH-01/UPLOAD-10 (D4) — the two recovery moves a creator can make from the dashboard once a
 * product is stuck: retry a FAILED upload, or republish an UNPUBLISHED one. Both are guarded
 * state transitions (same shape as {@code Order}/{@code Payment}'s {@code transitionTo}, just
 * without a dedicated enum method, because {@code Product} only has these two recovery edges,
 * not a full state machine to encode).
 *
 * Lives in the {@code creator} module, not {@code marketplace}'s {@link com.secureleaf.marketplace.service.ProductService}:
 * retrying processing needs {@link ProcessingJobRepository} (creator) and republishing needs to
 * check {@link com.secureleaf.content.entity.DocumentVersion} (content). Both of those already
 * depend on {@code marketplace.Product}, so putting the dependency here — rather than teaching
 * the marketplace module about jobs and document versions — avoids a package cycle.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProductRecoveryService {

    private final ProductRepository productRepository;
    private final ProcessingJobRepository processingJobRepository;
    private final DocumentVersionRepository documentVersionRepository;

    /**
     * FAILED → QUEUED (D4). Resets the most recent job's retry counter to zero and hands it back
     * to {@link com.secureleaf.content.service.ProcessingJobWorker}'s existing 5-second poll —
     * no separate "kick the pipeline" call needed, the same queue that processes new uploads
     * picks this job up on its own.
     */
    @Transactional
    public ProductDto retryProcessing(Long productId, Long creatorId) {
        return retryProcessing(productId, null, creatorId);
    }

    /**
     * Phase 15, D2 — generalised to a specific version. {@code versionId == null} keeps the Phase 6
     * meaning (the product's first upload FAILED). With a version id, the retry targets that
     * version's latest job, which must be FAILED; the product's own status is left alone (it is
     * still LIVE on its current version) unless it was FAILED because it has no current version.
     */
    @Transactional
    public ProductDto retryProcessing(Long productId, Long versionId, Long creatorId) {
        Product product = productRepository.findByIdAndDeletedAtIsNull(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product", productId));
        assertOwnership(product, creatorId);

        ProcessingJob job;
        if (versionId == null) {
            if (product.getStatus() != ProductStatus.FAILED) {
                throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                        "Product " + productId + " is not FAILED (currently " + product.getStatus() + ") — nothing to retry.");
            }
            job = processingJobRepository.findFirstByProductIdOrderByIdDesc(productId)
                    .orElseThrow(() -> new IllegalStateException("FAILED product " + productId + " has no processing job"));
        } else {
            DocumentVersion version = documentVersionRepository.findById(versionId)
                    .filter(v -> v.getProduct().getId().equals(productId))
                    .orElseThrow(() -> new ResourceNotFoundException("DocumentVersion", versionId));
            job = processingJobRepository.findFirstByDocumentVersionIdOrderByIdDesc(versionId)
                    .filter(j -> j.getStatus() == JobStatus.FAILED && version.getRetiredAt() == null)
                    .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                            "Version " + version.getVersionNumber() + " of product " + productId
                                    + " has no failed processing job to retry."));
            if (processingJobRepository.existsByProductIdAndStatusIn(productId,
                    java.util.List.of(JobStatus.QUEUED, JobStatus.PROCESSING))) {
                throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                        "Another version of product " + productId + " is still processing.");
            }
        }
        job.setStatus(JobStatus.QUEUED);
        job.setRetryCount(0);
        job.setFailureReason(null);
        job.setCurrentStage(null);
        job.setWorkerId(null);
        job.setClaimedAt(null);
        job.setStartedAt(null);
        job.setCompletedAt(null);
        processingJobRepository.save(job);

        Product saved = product;
        if (product.getStatus() == ProductStatus.FAILED) {
            product.setStatus(ProductStatus.PROCESSING);
            saved = productRepository.save(product);
        }

        log.info("Product {} retry requested by creator {}: job {} reset to QUEUED", productId, creatorId, job.getId());
        return ProductMapper.toDto(saved);
    }

    /**
     * UNPUBLISHED → LIVE (D4), only if a document version has actually finished processing and
     * the product isn't soft-deleted — {@code findByIdAndDeletedAtIsNull} already excludes the
     * latter, since a deleted product can never come back through this path.
     */
    @Transactional
    public ProductDto republishProduct(Long productId, Long creatorId) {
        Product product = productRepository.findByIdAndDeletedAtIsNull(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product", productId));
        assertOwnership(product, creatorId);

        if (product.getStatus() != ProductStatus.UNPUBLISHED) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Product " + productId + " is not UNPUBLISHED (currently " + product.getStatus() + ").");
        }
        // Phase 8, D5 — an admin takedown (product.takedownReason != null) can only be reversed
        // by an admin's restoreProduct. A creator's own unpublishProduct leaves this null, so
        // that path is unaffected.
        if (product.getTakedownReason() != null) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Product " + productId + " was taken down by an admin and cannot be republished. "
                            + "An admin must restore it first.");
        }

        // Phase 15, D1 — "has a processed version" now means "has a current-version pointer".
        if (product.getCurrentDocumentVersion() == null) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Product " + productId + " has no processed document version to republish.");
        }

        product.setStatus(ProductStatus.LIVE);
        Product saved = productRepository.save(product);

        log.info("Product {} republished by creator {}", productId, creatorId);
        return ProductMapper.toDto(saved);
    }

    /** Same BOLA guard as {@link com.secureleaf.marketplace.service.ProductService#unpublishProduct}. */
    private void assertOwnership(Product product, Long creatorId) {
        if (!product.getCreator().getId().equals(creatorId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED,
                    "You do not have permission to modify this product.");
        }
    }
}
