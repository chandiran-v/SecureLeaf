package com.secureleaf.content.controller;

import com.secureleaf.auth.service.SecureLeafUserDetails;
import com.secureleaf.common.config.MinioProperties;
import com.secureleaf.common.exception.BusinessException;
import com.secureleaf.common.exception.ErrorCode;
import com.secureleaf.common.storage.StorageService;
import com.secureleaf.content.dto.UploadResponseDto;
import com.secureleaf.content.entity.DocumentVersion;
import com.secureleaf.content.entity.UpdatePolicy;
import com.secureleaf.content.service.DocumentVersionService;
import com.secureleaf.content.repository.DocumentVersionRepository;
import com.secureleaf.creator.entity.JobStatus;
import com.secureleaf.creator.entity.ProcessingJob;
import com.secureleaf.creator.repository.ProcessingJobRepository;
import com.secureleaf.marketplace.entity.Product;
import com.secureleaf.marketplace.entity.ProductStatus;
import com.secureleaf.marketplace.repository.ProductRepository;
import com.secureleaf.marketplace.service.ProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;

/**
 * REST controller for PDF document uploads.
 *
 * Why REST instead of GraphQL?
 * GraphQL uses a JSON body — multipart file uploads require a separate protocol
 * (the multipart request spec, RFC 2046). While there IS a GraphQL multipart spec,
 * Spring for GraphQL doesn't support it out of the box. REST is the standard,
 * pragmatic choice for binary file uploads. (See CLAUDE.md: "REST for file upload/download")
 *
 * Security:
 * - The JWT filter (JwtAuthenticationFilter) runs first — the request must carry
 *   a valid Bearer token.
 * - @PreAuthorize("hasRole('CREATOR')") checks the CREATOR role.
 * - ProductService.getProductById + creator ID comparison checks ownership.
 * - Magic-byte validation (first 5 bytes == '%PDF-') rejects files that are not
 *   actually PDFs regardless of what Content-Type the client sends.
 *
 * Spring's multipart limits (55MB per file, 60MB per request) are configured
 * in application.yml — no changes needed here.
 */
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
@Slf4j
public class DocumentUploadController {

    /** "%PDF-" in ASCII bytes — the magic number for PDF files */
    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-'};
    /** 50MB in bytes */
    private static final long MAX_FILE_SIZE_BYTES = 50L * 1024 * 1024;

    private final ProductService productService;
    private final ProductRepository productRepository;
    private final DocumentVersionRepository documentVersionRepository;
    private final ProcessingJobRepository processingJobRepository;
    private final DocumentVersionService documentVersionService;
    private final StorageService storageService;
    private final MinioProperties minioProperties;

    /**
     * Accepts a PDF upload, validates it, stores it in MinIO, and enqueues
     * an async processing job.
     *
     * Returns 202 Accepted immediately — the actual PDF-to-tile conversion
     * happens asynchronously in the background. The caller polls myProducts
     * to watch status transition PROCESSING → LIVE.
     *
     * This endpoint is for the FIRST document only. A re-upload before the first version has
     * finished (e.g. after a failure) still replaces version 1; once the product has a current
     * version, new content goes through {@link #uploadNewVersion} (Phase 15) instead.
     */
    @PostMapping("/{productId}/document")
    @PreAuthorize("hasRole('CREATOR')")
    public ResponseEntity<UploadResponseDto> uploadDocument(
            @PathVariable Long productId,
            @RequestParam("file") MultipartFile file) throws IOException {

        Long creatorId = getCurrentUserId();
        log.info("[job-correlate] Upload started: productId={}, creatorId={}", productId, creatorId);

        // 1. Ownership check — ensure this creator owns the product
        Product product = productService.getProductById(productId);
        if (!product.getCreator().getId().equals(creatorId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED,
                    "You do not have permission to upload to this product.");
        }

        if (product.getCurrentDocumentVersion() != null) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Product " + productId + " already has a document. Upload a new edition with "
                            + "POST /api/products/{id}/versions.");
        }

        // 2-3. Size (UPLOAD-02) and magic-byte (UPLOAD-03) validation
        byte[] fileBytes = validatedPdfBytes(file);

        // 4. Store raw PDF in MinIO (UPLOAD-11)
        // The key encodes product ID and a UUID for uniqueness on re-upload.
        String objectKey = "products/%d/v1/%s.pdf".formatted(productId, UUID.randomUUID());
        String rawBucket = minioProperties.getBucket().getRawUploads();
        storageService.put(rawBucket, objectKey, fileBytes, "application/pdf");
        log.debug("[job-correlate] Raw PDF stored: bucket={}, key={}", rawBucket, objectKey);

        // 5. Upsert DocumentVersion (MVP 1: always version 1)
        // If a previous version exists, reuse it so we don't accumulate orphan rows.
        DocumentVersion docVersion = documentVersionRepository
                .findByProductIdAndVersionNumber(productId, 1)
                .orElseGet(DocumentVersion::new);
        docVersion.setProduct(product);
        docVersion.setVersionNumber(1);
        docVersion.setOriginalFilename(
                file.getOriginalFilename() != null ? file.getOriginalFilename() : "document.pdf");
        docVersion.setFileSizeBytes(file.getSize());
        docVersion.setRawMinioBucket(rawBucket);
        docVersion.setRawMinioObjectKey(objectKey);
        docVersion.setPageCount(null);        // set after processing
        docVersion.setThumbnailMinioKey(null); // set after processing
        docVersion.setProcessedAt(null);
        docVersion = documentVersionRepository.save(docVersion);

        // 6. Create or re-create processing job
        // Cancel any existing job for this product to avoid stale jobs.
        processingJobRepository.findAll().stream()
                .filter(j -> j.getProduct().getId().equals(productId)
                        && j.getStatus() != JobStatus.COMPLETED
                        && j.getStatus() != JobStatus.FAILED)
                .forEach(j -> {
                    j.setStatus(JobStatus.FAILED);
                    j.setFailureReason("Superseded by re-upload");
                    processingJobRepository.save(j);
                });

        ProcessingJob job = new ProcessingJob();
        job.setDocumentVersion(docVersion);
        job.setProduct(product);
        job.setStatus(JobStatus.QUEUED);
        job = processingJobRepository.save(job);

        // 7. Set product status to PROCESSING
        product.setStatus(ProductStatus.PROCESSING);
        productRepository.save(product);

        log.info("[job-correlate] Job queued: jobId={}, productId={}, docVersionId={}",
                job.getId(), productId, docVersion.getId());

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new UploadResponseDto(docVersion.getId(), job.getId(), "PROCESSING"));
    }

    /**
     * Phase 15, D2 — uploads a NEW VERSION of a product that already has a current version.
     *
     * Same validation as the first upload (50 MB, PDF magic bytes). The product stays LIVE on its
     * old version while this one processes; the pointer moves when the job completes
     * (see DocumentProcessingService.markLive). {@code updatePolicy} is the creator's choice for
     * buyers already on an older version (D3).
     */
    @PostMapping("/{productId}/versions")
    @PreAuthorize("hasRole('CREATOR')")
    public ResponseEntity<UploadResponseDto> uploadNewVersion(
            @PathVariable Long productId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "updatePolicy", defaultValue = "NEW_BUYERS_ONLY") String updatePolicy)
            throws IOException {

        Long creatorId = getCurrentUserId();
        log.info("[job-correlate] New version upload started: productId={}, creatorId={}", productId, creatorId);

        // Fail fast on ownership BEFORE reading 50 MB; DocumentVersionService re-checks under a row lock.
        Product product = productService.getProductById(productId);
        if (!product.getCreator().getId().equals(creatorId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED,
                    "You do not have permission to upload to this product.");
        }
        UpdatePolicy policy = parsePolicy(updatePolicy);
        byte[] fileBytes = validatedPdfBytes(file);

        // The key needs no version number (it isn't known until the row is created under the lock);
        // the UUID alone makes it unique. The version's tiles get deterministic keys later.
        String rawBucket = minioProperties.getBucket().getRawUploads();
        String objectKey = "products/%d/uploads/%s.pdf".formatted(productId, UUID.randomUUID());
        storageService.put(rawBucket, objectKey, fileBytes, "application/pdf");

        try {
            UploadResponseDto response = documentVersionService.createVersion(productId, creatorId,
                    file.getOriginalFilename(), file.getSize(), rawBucket, objectKey, policy);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
        } catch (RuntimeException e) {
            // Rejected (another version in flight, no current version, …): don't leave an orphan PDF.
            storageService.delete(rawBucket, objectKey);
            throw e;
        }
    }

    private byte[] validatedPdfBytes(MultipartFile file) throws IOException {
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE,
                    "File exceeds the 50MB limit. Actual size: %d bytes".formatted(file.getSize()));
        }
        // Never trust the client's Content-Type — anyone can send "application/pdf"
        // with a PNG, JavaScript, or executable inside. Read the actual first bytes.
        byte[] fileBytes = file.getBytes();
        if (!hasPdfMagicBytes(fileBytes)) {
            throw new BusinessException(ErrorCode.INVALID_FILE,
                    "File is not a valid PDF. Only PDF files are accepted.");
        }
        return fileBytes;
    }

    private static UpdatePolicy parsePolicy(String raw) {
        try {
            return UpdatePolicy.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "updatePolicy must be NEW_BUYERS_ONLY or FREE_UPDATE_FOR_EXISTING.");
        }
    }

    /**
     * Checks the first 5 bytes of the file for the PDF magic number "%PDF-".
     *
     * Magic bytes (also called file signatures) are the first few bytes of a file
     * that identify its true format, regardless of file extension or MIME type.
     * PDFs always start with "%PDF-" (hex: 25 50 44 46 2D).
     *
     * This prevents: uploading a .png renamed to .pdf, uploading a script, etc.
     */
    private boolean hasPdfMagicBytes(byte[] bytes) {
        if (bytes.length < PDF_MAGIC.length) return false;
        return Arrays.equals(Arrays.copyOf(bytes, PDF_MAGIC.length), PDF_MAGIC);
    }

    private Long getCurrentUserId() {
        SecureLeafUserDetails userDetails = (SecureLeafUserDetails) SecurityContextHolder
                .getContext().getAuthentication().getPrincipal();
        return userDetails.getUserId();
    }
}
