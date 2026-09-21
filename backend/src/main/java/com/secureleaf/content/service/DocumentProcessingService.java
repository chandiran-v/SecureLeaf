package com.secureleaf.content.service;

import com.secureleaf.common.config.MinioProperties;
import com.secureleaf.common.storage.StorageService;
import com.secureleaf.content.entity.ContentPage;
import com.secureleaf.content.entity.DocumentVersion;
import com.secureleaf.content.repository.ContentPageRepository;
import com.secureleaf.content.repository.DocumentVersionRepository;
import com.secureleaf.creator.entity.JobStage;
import com.secureleaf.creator.entity.JobStatus;
import com.secureleaf.creator.entity.ProcessingJob;
import com.secureleaf.creator.repository.ProcessingJobRepository;
import com.secureleaf.marketplace.entity.Product;
import com.secureleaf.marketplace.entity.ProductStatus;
import com.secureleaf.marketplace.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;

/**
 * 5-stage document processing pipeline.
 *
 * Runs @Async on the contentProcessingExecutor thread pool so it never
 * blocks a Tomcat servlet thread or the @Scheduled poller thread.
 *
 * State machine: each job advances through stages in order.
 * Why a state machine instead of booleans?
 * Two booleans (isProcessed, isFailed) give 4 states but can be
 * meaningless in combination (isProcessed=true AND isFailed=true?).
 * An enum state machine makes illegal states unrepresentable and lets
 * us see exactly where in the pipeline a job stalled when debugging.
 *
 * Idempotency: every stage is designed to be safe to re-run:
 * - Tiles overwrite by deterministic MinIO key (products/{id}/v{v}/tiles/{page}.png)
 * - ContentPage inserts are guarded by the uq_content_pages_version_page constraint
 *   (we delete-before-insert to avoid conflicts on retry)
 * - Thumbnail overwrites the same key on retry
 *
 * Failure handling (UPLOAD-10):
 * If a stage throws, retryCount is incremented.
 * If retryCount < maxRetries: job goes back to QUEUED for the next poll cycle.
 * If retryCount >= maxRetries: job → FAILED, product → FAILED.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentProcessingService {

    private static final float TILE_DPI = 150f;
    private static final int THUMBNAIL_WIDTH_PX = 400;

    private final ProcessingJobRepository processingJobRepository;
    private final DocumentVersionRepository documentVersionRepository;
    private final ContentPageRepository contentPageRepository;
    private final ProductRepository productRepository;
    private final StorageService storageService;
    private final MinioProperties minioProperties;

    /**
     * Entry point called by ProcessingJobWorker.
     * @Async puts execution on the contentProcessingExecutor thread pool.
     * @Transactional wraps the entire pipeline in one transaction; if the
     * server crashes mid-pipeline, the job stays in PROCESSING state and
     * will be re-picked on the next restart (or by a watchdog, future work).
     */
    @Async("contentProcessingExecutor")
    @Transactional
    public void processAsync(Long jobId) {
        ProcessingJob job = processingJobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalStateException("Job not found: " + jobId));

        log.info("[job-{}] Processing started", jobId);

        try {
            runPipeline(job);
        } catch (Exception e) {
            handleFailure(job, e);
        }
    }

    // ── Pipeline ─────────────────────────────────────────────────────────────

    private void runPipeline(ProcessingJob job) throws Exception {
        DocumentVersion docVersion = documentVersionRepository.findById(
                job.getDocumentVersion().getId())
                .orElseThrow();
        Product product = productRepository.findById(job.getProduct().getId())
                .orElseThrow();

        // Load the raw PDF bytes once — used by multiple stages
        byte[] pdfBytes = storageService.get(
                docVersion.getRawMinioBucket(),
                docVersion.getRawMinioObjectKey());

        try (PDDocument pdf = Loader.loadPDF(pdfBytes)) {
            // Stage 1: VALIDATE
            advanceStage(job, JobStage.VALIDATE);
            validatePdf(pdf, job.getId());

            int pageCount = pdf.getNumberOfPages();

            // Stage 2: CONVERT_TILES
            advanceStage(job, JobStage.CONVERT_TILES);
            convertTiles(pdf, docVersion, product.getId(), pageCount, job.getId());

            // Stage 3: GENERATE_THUMBNAIL
            advanceStage(job, JobStage.GENERATE_THUMBNAIL);
            generateThumbnail(pdf, docVersion, product, job.getId());

            // Stage 4: GENERATE_PREVIEW (no-op for MVP)
            // The free-preview boundary is enforced at serve-time using
            // product.freePreviewPages. Nothing to pre-render.
            advanceStage(job, JobStage.GENERATE_PREVIEW);
            log.debug("[job-{}] GENERATE_PREVIEW: no-op for MVP, boundary enforced at serve time", job.getId());

            // Stage 5: MARK_LIVE
            advanceStage(job, JobStage.MARK_LIVE);
            markLive(job, docVersion, product, pageCount);
        }
    }

    // ── Stage implementations ─────────────────────────────────────────────────

    private void validatePdf(PDDocument pdf, Long jobId) {
        if (pdf.isEncrypted()) {
            throw new IllegalArgumentException("PDF is encrypted. Only unencrypted PDFs are accepted.");
        }
        if (pdf.getNumberOfPages() < 1) {
            throw new IllegalArgumentException("PDF has no pages.");
        }
        log.debug("[job-{}] VALIDATE: {} pages, not encrypted", jobId, pdf.getNumberOfPages());
    }

    private void convertTiles(PDDocument pdf, DocumentVersion docVersion,
                               Long productId, int pageCount, Long jobId) throws IOException {
        PDFRenderer renderer = new PDFRenderer(pdf);
        String tilesBucket = minioProperties.getBucket().getTiles();

        // Idempotent: clear existing ContentPage rows before re-inserting.
        // Without this, a retry would hit the unique constraint uq_content_pages_version_page.
        contentPageRepository.deleteByDocumentVersionId(docVersion.getId());

        for (int i = 0; i < pageCount; i++) {
            int pageNo = i + 1; // 1-indexed

            // renderImageWithDPI renders a full-resolution raster of one page.
            // 150 DPI is high enough for readable text, small enough for fast load.
            BufferedImage image = renderer.renderImageWithDPI(i, TILE_DPI);
            byte[] pngBytes = toPngBytes(image);

            // Deterministic key: same key on retry → MinIO overwrites silently
            String key = "products/%d/v%d/tiles/%d.png"
                    .formatted(productId, docVersion.getVersionNumber(), pageNo);

            storageService.put(tilesBucket, key, pngBytes, "image/png");

            // Record the page in the DB
            ContentPage page = new ContentPage();
            page.setDocumentVersion(docVersion);
            page.setPageNumber(pageNo);
            page.setBucketName(tilesBucket);
            page.setMinioObjectKey(key);
            page.setWidthPx(image.getWidth());
            page.setHeightPx(image.getHeight());
            page.setFileSizeBytes((long) pngBytes.length);
            try {
                contentPageRepository.save(page);
            } catch (DataIntegrityViolationException e) {
                // Race condition on retry — the unique constraint fired but the
                // DELETE-before-INSERT should have prevented this. Log and continue.
                log.warn("[job-{}] ContentPage already exists for page {}, skipping", jobId, pageNo);
            }

            log.debug("[job-{}] Tile {}/{} written: {}x{}px, {} bytes",
                    jobId, pageNo, pageCount, image.getWidth(), image.getHeight(), pngBytes.length);
        }
    }

    private void generateThumbnail(PDDocument pdf, DocumentVersion docVersion,
                                    Product product, Long jobId) throws IOException {
        PDFRenderer renderer = new PDFRenderer(pdf);
        // Render page 1 at 72 DPI (screen resolution) — we scale it down anyway
        BufferedImage firstPage = renderer.renderImageWithDPI(0, 72f);

        // Scale to ~400px wide, preserving aspect ratio
        int thumbHeight = (int) ((double) firstPage.getHeight() / firstPage.getWidth() * THUMBNAIL_WIDTH_PX);
        BufferedImage thumbnail = new BufferedImage(THUMBNAIL_WIDTH_PX, thumbHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = thumbnail.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(firstPage, 0, 0, THUMBNAIL_WIDTH_PX, thumbHeight, null);
        g.dispose();

        byte[] thumbBytes = toPngBytes(thumbnail);
        String thumbBucket = minioProperties.getBucket().getThumbnails();
        String thumbKey = "products/%d/v%d/thumbnail.png"
                .formatted(product.getId(), docVersion.getVersionNumber());

        storageService.put(thumbBucket, thumbKey, thumbBytes, "image/png");

        // Update DocumentVersion and Product with the thumbnail key
        docVersion.setThumbnailMinioKey(thumbKey);
        documentVersionRepository.save(docVersion);

        // SECURITY: coverImageUrl stores the MinIO *key*, NOT a signed URL.
        // A signed URL is generated per-request in the viewer (Phase 5).
        // This field is used by the creator dashboard only to show a thumbnail preview
        // — the frontend calls a separate endpoint to get a short-lived URL.
        product.setCoverImageUrl(thumbKey);
        productRepository.save(product);

        log.debug("[job-{}] Thumbnail generated: {}x{}px, {} bytes", jobId,
                THUMBNAIL_WIDTH_PX, thumbHeight, thumbBytes.length);
    }

    private void markLive(ProcessingJob job, DocumentVersion docVersion,
                           Product product, int pageCount) {
        docVersion.setPageCount(pageCount);
        docVersion.setProcessedAt(Instant.now());
        documentVersionRepository.save(docVersion);

        product.setStatus(ProductStatus.LIVE);
        productRepository.save(product);

        job.setStatus(JobStatus.COMPLETED);
        job.setCompletedAt(Instant.now());
        processingJobRepository.save(job);

        log.info("[job-{}] Pipeline complete: product {} is now LIVE ({} pages)",
                job.getId(), product.getId(), pageCount);
    }

    // ── Failure handling ─────────────────────────────────────────────────────

    private void handleFailure(ProcessingJob job, Exception e) {
        log.error("[job-{}] Stage {} failed (attempt {}/{}): {}",
                job.getId(), job.getCurrentStage(), job.getRetryCount() + 1,
                job.getMaxRetries(), e.getMessage(), e);

        job.setRetryCount(job.getRetryCount() + 1);

        if (job.getRetryCount() < job.getMaxRetries()) {
            // Return to QUEUED so the next poll cycle picks it up again
            job.setStatus(JobStatus.QUEUED);
            job.setWorkerId(null);
            job.setClaimedAt(null);
            processingJobRepository.save(job);
            log.warn("[job-{}] Will retry (attempt {} of {})", job.getId(),
                    job.getRetryCount(), job.getMaxRetries());
        } else {
            // Exhausted retries — mark job and product as FAILED
            job.setStatus(JobStatus.FAILED);
            job.setFailureReason(e.getMessage());
            job.setCompletedAt(Instant.now());
            processingJobRepository.save(job);

            productRepository.findById(job.getProduct().getId()).ifPresent(p -> {
                p.setStatus(ProductStatus.FAILED);
                productRepository.save(p);
            });

            log.error("[job-{}] Processing permanently failed after {} attempts",
                    job.getId(), job.getMaxRetries());
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void advanceStage(ProcessingJob job, JobStage stage) {
        job.setCurrentStage(stage);
        processingJobRepository.save(job);
        log.debug("[job-{}] Stage: {}", job.getId(), stage);
    }

    private byte[] toPngBytes(BufferedImage image) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(image, "PNG", baos);
            return baos.toByteArray();
        }
    }
}
