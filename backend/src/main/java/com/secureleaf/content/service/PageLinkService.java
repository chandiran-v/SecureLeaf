package com.secureleaf.content.service;

import com.secureleaf.common.storage.StorageService;
import com.secureleaf.content.entity.ContentPage;
import com.secureleaf.content.entity.ContentPageLink;
import com.secureleaf.content.entity.DocumentVersion;
import com.secureleaf.content.repository.ContentPageLinkRepository;
import com.secureleaf.content.repository.ContentPageRepository;
import com.secureleaf.content.repository.DocumentVersionRepository;
import com.secureleaf.content.tiles.TileVariantProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * Stores the clickable links of rendered pages (V8), both for new uploads (called from the
 * processing pipeline while it already has the PDF open) and for documents processed before
 * links existed ({@link #backfill}, driven by {@link PageLinkBackfillJob}).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PageLinkService {

    private final PdfLinkExtractor extractor;
    private final ContentPageLinkRepository linkRepository;
    private final ContentPageRepository contentPageRepository;
    private final DocumentVersionRepository documentVersionRepository;
    private final StorageService storageService;

    /** Extracts page {@code page.pageNumber}'s links from the open PDF and saves them. */
    public int saveLinks(PDDocument pdf, ContentPage page) {
        List<ContentPageLink> links = extractor.extract(pdf, page.getPageNumber() - 1).stream()
                .map(extracted -> {
                    ContentPageLink link = new ContentPageLink();
                    link.setContentPage(page);
                    link.setLeftRatio(extracted.left());
                    link.setTopRatio(extracted.top());
                    link.setWidthRatio(extracted.width());
                    link.setHeightRatio(extracted.height());
                    link.setLinkType(extracted.type());
                    link.setTargetUrl(extracted.url());
                    link.setTargetPage(extracted.targetPage());
                    return link;
                })
                .toList();
        linkRepository.saveAll(links);
        return links.size();
    }

    /**
     * Extracts links for a version processed before V8, from its stored raw PDF. Always stamps
     * {@code links_extracted_at}, even when the PDF can't be read: links are a nicety, and a
     * missing file must not be retried every few minutes forever.
     */
    @Transactional
    public void backfill(Long documentVersionId) {
        DocumentVersion version = documentVersionRepository.findById(documentVersionId).orElse(null);
        if (version == null || version.getLinksExtractedAt() != null) return; // gone, or already done

        int total = 0;
        try {
            byte[] pdfBytes = storageService.get(version.getRawMinioBucket(), version.getRawMinioObjectKey());
            try (PDDocument pdf = Loader.loadPDF(pdfBytes)) {
                for (ContentPage page : contentPageRepository.findByDocumentVersionIdAndVariantOrderByPageNumber(
                        documentVersionId, TileVariantProperties.DESKTOP)) {
                    if (page.getPageNumber() <= pdf.getNumberOfPages()) {
                        total += saveLinks(pdf, page);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Link backfill for document version {} failed; marking it done without links: {}",
                    documentVersionId, e.getMessage());
        }
        version.setLinksExtractedAt(Instant.now());
        documentVersionRepository.save(version);
        log.info("Link backfill: document version {} → {} link(s)", documentVersionId, total);
    }
}
