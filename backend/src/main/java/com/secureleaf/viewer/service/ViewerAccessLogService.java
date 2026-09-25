package com.secureleaf.viewer.service;

import com.secureleaf.content.entity.ContentPage;
import com.secureleaf.content.entity.DocumentVersion;
import com.secureleaf.viewer.entity.ViewerAccessLog;
import com.secureleaf.viewer.entity.ViewerSession;
import com.secureleaf.viewer.repository.ViewerAccessLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * D9 — one {@code viewer_access_logs} row per *successful* tile response, written
 * synchronously, in the same request/transaction as the response.
 *
 * WHY SYNCHRONOUS, NOT A QUEUE
 * A queue (Kafka, an outbox table + async worker) decouples the write from the request so a
 * slow audit sink can never slow down page loads — the right call at real scale. At MVP scale
 * (single Postgres, tens of concurrent viewers) that decoupling is pure complexity: one more
 * moving part, one more place a log row can silently go missing if the consumer crashes. A
 * plain {@code INSERT} in the same transaction as the tile response is simpler and, at this
 * scale, cheaper — it would change the moment audit-log writes started measurably slowing down
 * tile responses, or the audit trail needed to survive the tile service being down.
 */
@Service
@RequiredArgsConstructor
public class ViewerAccessLogService {

    private final ViewerAccessLogRepository viewerAccessLogRepository;

    @Transactional
    public void record(ViewerSession session, DocumentVersion documentVersion, ContentPage contentPage,
                        int pageNumber, String ipAddress, String userAgent, String correlationId) {
        ViewerAccessLog log = new ViewerAccessLog();
        log.setViewerSession(session);
        log.setUser(session.getUser());
        log.setProduct(session.getProduct());
        log.setDocumentVersion(documentVersion);
        log.setContentPage(contentPage);
        log.setPageNumber(pageNumber);
        log.setIpAddress(ipAddress);
        log.setUserAgent(userAgent);
        log.setCorrelationId(correlationId);
        viewerAccessLogRepository.save(log);
    }
}
