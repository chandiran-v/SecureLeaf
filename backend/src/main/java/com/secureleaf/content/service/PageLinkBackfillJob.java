package com.secureleaf.content.service;

import com.secureleaf.content.entity.DocumentVersion;
import com.secureleaf.content.repository.DocumentVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Gives documents uploaded before V8 their clickable links: every few minutes, extract links
 * for up to 20 processed versions that don't have them yet. Idempotent (each version is stamped
 * once) and self-finishing: once every old version is done, each run is one cheap empty query.
 *
 * <p>Disabled in tests ({@code content.links.backfill.enabled=false}) so it can't add
 * background queries to query-count assertions; {@code PageLinkBackfillIT} calls the service.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "content.links.backfill.enabled", havingValue = "true", matchIfMissing = true)
public class PageLinkBackfillJob {

    private final DocumentVersionRepository documentVersionRepository;
    private final PageLinkService pageLinkService;

    @Scheduled(initialDelayString = "${content.links.backfill.initial-delay-ms:30000}",
               fixedDelayString = "${content.links.backfill.interval-ms:300000}")
    public void run() {
        List<DocumentVersion> pending = documentVersionRepository.findTop20ByLinksExtractedAtIsNullAndProcessedAtIsNotNullOrderByIdAsc();
        for (DocumentVersion version : pending) {
            pageLinkService.backfill(version.getId()); // own transaction per version
        }
    }
}
