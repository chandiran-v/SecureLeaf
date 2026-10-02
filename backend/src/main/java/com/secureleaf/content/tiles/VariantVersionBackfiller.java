package com.secureleaf.content.tiles;

import com.secureleaf.common.config.MinioProperties;
import com.secureleaf.common.storage.StorageService;
import com.secureleaf.content.entity.ContentPage;
import com.secureleaf.content.entity.DocumentVersion;
import com.secureleaf.content.repository.ContentPageRepository;
import com.secureleaf.content.repository.DocumentVersionRepository;
import com.secureleaf.content.service.DocumentProcessingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * Phase 16, D4 — renders the pages of ONE version that lack ONE variant. Its own bean (not a method
 * of {@link VariantBackfillService}) so the {@code @Transactional} applies through the Spring proxy:
 * each version commits on its own, so a stopped run loses at most the version in flight.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VariantVersionBackfiller {

    private final DocumentVersionRepository documentVersionRepository;
    private final ContentPageRepository contentPageRepository;
    private final StorageService storageService;
    private final MinioProperties minioProperties;
    private final TileVariantProperties tileVariants;
    private final PageTileRenderer pageTileRenderer;

    /** @return how many page rows were created; 0 when nothing was missing (idempotent). */
    @Transactional
    public int backfill(Long documentVersionId, String variantName) throws IOException {
        DocumentVersion version = documentVersionRepository.findById(documentVersionId).orElse(null);
        if (version == null || version.getProcessedAt() == null || version.getRetiredAt() != null
                || version.getPageCount() == null) {
            return 0;
        }
        TileVariantProperties.Variant variant = tileVariants.resolve(variantName);

        // Page-level resume: only pages without a row for this variant are rendered.
        Set<Integer> have = new HashSet<>();
        for (ContentPage p : contentPageRepository.findByDocumentVersionIdAndVariantOrderByPageNumber(
                documentVersionId, variant.name())) {
            have.add(p.getPageNumber());
        }
        if (have.size() >= version.getPageCount()) return 0;

        String bucket = minioProperties.getBucket().getTiles();
        byte[] pdfBytes = storageService.get(version.getRawMinioBucket(), version.getRawMinioObjectKey());
        int created = 0;
        try (PDDocument pdf = Loader.loadPDF(pdfBytes)) {
            PDFRenderer renderer = new PDFRenderer(pdf);
            int pages = Math.min(version.getPageCount(), pdf.getNumberOfPages());
            for (int pageNo = 1; pageNo <= pages; pageNo++) {
                if (have.contains(pageNo)) continue;
                BufferedImage image = pageTileRenderer.render(pdf, renderer, pageNo - 1, variant);
                byte[] png = DocumentProcessingService.toPngBytes(image);
                String key = DocumentProcessingService.tileKey(
                        version.getProduct().getId(), version.getVersionNumber(), variant.name(), pageNo);
                storageService.put(bucket, key, png, "image/png");
                contentPageRepository.save(DocumentProcessingService.newContentPage(
                        version, pageNo, variant.name(), bucket, key, image, png));
                created++;
            }
        }
        log.info("Variant backfill: document version {} → {} {} page(s)", documentVersionId, created, variant.name());
        return created;
    }
}
