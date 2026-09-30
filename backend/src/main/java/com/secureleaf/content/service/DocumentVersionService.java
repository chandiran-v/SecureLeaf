package com.secureleaf.content.service;

import com.secureleaf.commerce.entity.EntitlementStatus;
import com.secureleaf.commerce.repository.EntitlementRepository;
import com.secureleaf.common.exception.BusinessException;
import com.secureleaf.common.exception.ErrorCode;
import com.secureleaf.common.exception.ResourceNotFoundException;
import com.secureleaf.common.storage.StorageService;
import com.secureleaf.content.dto.DocumentVersionDto;
import com.secureleaf.content.dto.UploadResponseDto;
import com.secureleaf.content.entity.ContentPage;
import com.secureleaf.content.entity.DocumentVersion;
import com.secureleaf.content.entity.UpdatePolicy;
import com.secureleaf.content.repository.ContentPageRepository;
import com.secureleaf.content.repository.DocumentVersionRepository;
import com.secureleaf.creator.entity.JobStatus;
import com.secureleaf.creator.entity.ProcessingJob;
import com.secureleaf.creator.repository.ProcessingJobRepository;
import com.secureleaf.marketplace.entity.Product;
import com.secureleaf.marketplace.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 15 — creating, listing and retiring the versions of a product.
 *
 * The mental model (D1): a version is IMMUTABLE once processed; "what readers see by default" is a
 * pointer ({@code products.current_document_version_id}) that moves. Same idea as a git branch
 * pointing at commits, or a Docker tag pointing at an image digest. Buyers' entitlements point at a
 * specific version, never at "latest", so moving the pointer cannot take away what anyone paid for.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentVersionService {

    private final ProductRepository productRepository;
    private final DocumentVersionRepository documentVersionRepository;
    private final ProcessingJobRepository processingJobRepository;
    private final EntitlementRepository entitlementRepository;
    private final ContentPageRepository contentPageRepository;
    private final StorageService storageService;

    /**
     * D2 — registers version {@code max + 1} and queues its processing job. The product row is
     * locked for the transaction so two simultaneous uploads get distinct numbers and the
     * one-in-flight rule can't be raced. The product itself is NOT touched: it stays LIVE on its
     * current version until the job completes and moves the pointer.
     */
    @Transactional
    public UploadResponseDto createVersion(Long productId, Long creatorId, String filename, long sizeBytes,
                                           String rawBucket, String rawKey, UpdatePolicy policy) {
        Product product = lockOwnedProduct(productId, creatorId);
        if (product.getCurrentDocumentVersion() == null) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Product " + productId + " has no processed version yet. Upload the first document with "
                            + "POST /api/products/{id}/document.");
        }
        if (processingJobRepository.existsByProductIdAndStatusIn(productId,
                List.of(JobStatus.QUEUED, JobStatus.PROCESSING))) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Another version of product " + productId + " is still processing. Wait for it to finish.");
        }

        DocumentVersion version = new DocumentVersion();
        version.setProduct(product);
        version.setVersionNumber(documentVersionRepository.maxVersionNumber(productId) + 1);
        version.setOriginalFilename(filename != null ? filename : "document.pdf");
        version.setFileSizeBytes(sizeBytes);
        version.setRawMinioBucket(rawBucket);
        version.setRawMinioObjectKey(rawKey);
        version.setUpdatePolicy(policy);
        version = documentVersionRepository.save(version);

        ProcessingJob job = new ProcessingJob();
        job.setDocumentVersion(version);
        job.setProduct(product);
        job.setStatus(JobStatus.QUEUED);
        job = processingJobRepository.save(job);

        log.info("[job-correlate] Version queued: productId={}, version={}, policy={}, jobId={}",
                productId, version.getVersionNumber(), policy, job.getId());
        return new UploadResponseDto(version.getId(), job.getId(), "PROCESSING");
    }

    /** D7 — the version history, newest first, owner only. */
    @Transactional(readOnly = true)
    public List<DocumentVersionDto> listVersions(Long productId, Long creatorId) {
        Product product = productRepository.findByIdAndDeletedAtIsNull(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product", productId));
        assertOwnership(product, creatorId);

        List<DocumentVersion> versions = documentVersionRepository.findByProductIdOrderByVersionNumberDesc(productId);
        if (versions.isEmpty()) return List.of();

        Map<Long, ProcessingJob> latestJob = new HashMap<>();
        for (ProcessingJob j : processingJobRepository.findByDocumentVersionIdInOrderByIdDesc(
                versions.stream().map(DocumentVersion::getId).toList())) {
            latestJob.putIfAbsent(j.getDocumentVersion().getId(), j);   // highest id first
        }
        Map<Long, Integer> buyers = new HashMap<>();
        for (Object[] row : entitlementRepository.countByVersionForProduct(productId, EntitlementStatus.ACTIVE)) {
            buyers.put((Long) row[0], ((Long) row[1]).intValue());
        }
        Long currentId = product.getCurrentDocumentVersion() != null ? product.getCurrentDocumentVersion().getId() : null;

        return versions.stream().map(v -> {
            ProcessingJob job = latestJob.get(v.getId());
            String status = statusOf(v, job);
            boolean pending = v.getUpdatePolicy() == UpdatePolicy.FREE_UPDATE_FOR_EXISTING
                    && v.getProcessedAt() != null && v.getEntitlementsMigratedAt() == null;
            return new DocumentVersionDto(
                    v.getId(), v.getVersionNumber(),
                    v.getCreatedAt() != null ? v.getCreatedAt().atOffset(ZoneOffset.UTC) : null,
                    v.getPageCount(), status, v.getUpdatePolicy().name(),
                    buyers.getOrDefault(v.getId(), 0), v.getId().equals(currentId),
                    "FAILED".equals(status) && job != null ? job.getFailureReason() : null,
                    pending);
        }).toList();
    }

    /**
     * D5 — soft retirement. Refused while the version is current, still processing, or has ANY
     * entitlement pointing at it. Only when nobody can possibly be reading it are its tile objects
     * removed; the rows (and the raw PDF) stay, because viewer access logs reference the pages.
     */
    @Transactional
    public void retireVersion(Long productId, Long versionId, Long creatorId) {
        Product product = lockOwnedProduct(productId, creatorId);
        DocumentVersion version = documentVersionRepository.findById(versionId)
                .filter(v -> v.getProduct().getId().equals(productId))
                .orElseThrow(() -> new ResourceNotFoundException("DocumentVersion", versionId));

        if (version.getRetiredAt() != null) return;   // idempotent
        if (product.getCurrentDocumentVersion() != null
                && product.getCurrentDocumentVersion().getId().equals(versionId)) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Version " + version.getVersionNumber() + " is the current version and cannot be retired.");
        }
        if (entitlementRepository.existsByDocumentVersionId(versionId)) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Version " + version.getVersionNumber() + " still has buyers and cannot be retired.");
        }
        ProcessingJob job = processingJobRepository.findFirstByDocumentVersionIdOrderByIdDesc(versionId).orElse(null);
        if (job != null && (job.getStatus() == JobStatus.QUEUED || job.getStatus() == JobStatus.PROCESSING)) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Version " + version.getVersionNumber() + " is still processing.");
        }

        for (ContentPage page : contentPageRepository.findByDocumentVersionIdOrderByPageNumber(versionId)) {
            storageService.delete(page.getBucketName(), page.getMinioObjectKey());
        }
        version.setRetiredAt(Instant.now());
        documentVersionRepository.save(version);
        log.info("Version retired: productId={}, version={}", productId, version.getVersionNumber());
    }

    private static String statusOf(DocumentVersion v, ProcessingJob job) {
        if (v.getRetiredAt() != null) return "RETIRED";
        if (v.getProcessedAt() != null) return "READY";
        if (job != null && job.getStatus() == JobStatus.FAILED) return "FAILED";
        return "PROCESSING";
    }

    private Product lockOwnedProduct(Long productId, Long creatorId) {
        Product product = productRepository.findByIdForUpdate(productId)
                .filter(p -> p.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Product", productId));
        assertOwnership(product, creatorId);
        return product;
    }

    /** BOLA guard — same shape as ProductService.assertOwnership. */
    private void assertOwnership(Product product, Long creatorId) {
        if (!product.getCreator().getId().equals(creatorId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED,
                    "You do not have permission to modify this product.");
        }
    }
}
