package com.secureleaf.viewer.service;

import com.secureleaf.commerce.entity.Entitlement;
import com.secureleaf.commerce.entity.EntitlementStatus;
import com.secureleaf.commerce.repository.EntitlementRepository;
import com.secureleaf.common.exception.BusinessException;
import com.secureleaf.common.exception.ErrorCode;
import com.secureleaf.common.exception.ResourceNotFoundException;
import com.secureleaf.common.storage.StorageService;
import com.secureleaf.content.entity.ContentPage;
import com.secureleaf.content.entity.DocumentVersion;
import com.secureleaf.content.repository.ContentPageRepository;
import com.secureleaf.content.watermark.WatermarkRenderer;
import com.secureleaf.viewer.entity.ViewerSession;
import com.secureleaf.viewer.entity.ViewerSessionEndReason;
import com.secureleaf.viewer.repository.ViewerSessionRepository;
import com.secureleaf.viewer.security.TileUrlSigner;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * D6 — the tile endpoint's checks, in order, then the watermarked bytes. The core invariant this
 * class exists to hold (same as {@code PreviewService} in Phase 3): every byte this class returns
 * has gone through {@link WatermarkRenderer} first. There is no path here that returns clean
 * storage bytes.
 *
 * Every check below throws before touching storage, in the exact order D6 specifies — cheapest
 * and least-sensitive checks (signature shape) first, so a probing attacker learns nothing about
 * *why* a request failed beyond "you don't get a tile."
 */
@Service
@RequiredArgsConstructor
public class SecureTileService {

    private static final String ACTIVE_KEY_PREFIX = "viewer:active:";
    private static final String USED_SIG_KEY_PREFIX = "viewer:used-sig:";
    private static final Duration USED_SIG_TTL = Duration.ofSeconds(60);
    private static final DateTimeFormatter WATERMARK_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ViewerSessionRepository viewerSessionRepository;
    private final EntitlementRepository entitlementRepository;
    private final ContentPageRepository contentPageRepository;
    private final StorageService storageService;
    private final WatermarkRenderer watermarkRenderer;
    private final TileUrlSigner tileUrlSigner;
    private final ViewerAccessLogService viewerAccessLogService;
    private final StringRedisTemplate redisTemplate;
    private final Clock clock;

    public record TileRequest(Long sessionId, int pageNumber, long exp, String signature,
                               Long callerUserId, String ipAddress, String userAgent, String correlationId) {
    }

    @Transactional
    public byte[] getTile(TileRequest request) {
        // D6 steps 2 & 4 — the signature is computed over (sessionId|pageNumber|userId|exp), so
        // verifying it with the CALLER's own userId does double duty: it proves the URL wasn't
        // tampered with (2) AND that it was issued to this exact caller (4) in one comparison —
        // a URL signed for user A simply fails to verify when checked against user B's id.
        if (!tileUrlSigner.verify(request.sessionId(), request.pageNumber(), request.callerUserId(),
                request.exp(), request.signature())) {
            throw new BusinessException(ErrorCode.SIGNED_URL_INVALID, "Signed URL is invalid, expired, or tampered.");
        }

        // D6 step 3 — single-use. SET NX is the right primitive here (unlike D3's active-session
        // pointer): the FIRST request to present this exact signature wins; every replay, even a
        // legitimate-looking one within the 30s window, must fail.
        String usedKey = USED_SIG_KEY_PREFIX + request.signature();
        Boolean firstUse = redisTemplate.opsForValue().setIfAbsent(usedKey, "1", USED_SIG_TTL);
        if (firstUse == null || !firstUse) {
            throw new BusinessException(ErrorCode.SIGNED_URL_INVALID, "This signed URL has already been used.");
        }

        ViewerSession session = viewerSessionRepository.findById(request.sessionId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SIGNED_URL_INVALID, "Unknown viewer session."));

        // D6 step 5 — must be the active session for this buyer+product right now.
        ViewerSessionEndReason inactiveReason = inactiveReason(session);
        if (inactiveReason == ViewerSessionEndReason.SUPERSEDED) {
            throw new BusinessException(ErrorCode.VIEWER_SESSION_SUPERSEDED,
                    "This viewer session was superseded by a newer session.");
        }
        if (inactiveReason != null) {
            throw new BusinessException(ErrorCode.VIEWER_SESSION_EXPIRED, "This viewer session has expired.");
        }

        // D6 step 6 — re-check the entitlement fresh (not the one cached on the session at start
        // time): a creator/admin can revoke mid-session, and the very next tile fetch must 403.
        Entitlement entitlement = entitlementRepository.findById(session.getEntitlement().getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_ENTITLED, "Entitlement no longer exists."));
        if (entitlement.getStatus() != EntitlementStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.NOT_ENTITLED, "Your entitlement for this product is no longer active.");
        }

        // D6 step 7 — page range for the ENTITLED document_version_id (D8: not necessarily the
        // product's current version, and status/deleted_at play no part here at all).
        DocumentVersion documentVersion = entitlement.getDocumentVersion();
        Integer pageCount = documentVersion.getPageCount();
        if (pageCount == null || request.pageNumber() < 1 || request.pageNumber() > pageCount) {
            throw new ResourceNotFoundException("ViewerPage", "pageNumber", request.pageNumber());
        }
        ContentPage contentPage = contentPageRepository
                .findByDocumentVersionIdAndPageNumber(documentVersion.getId(), request.pageNumber())
                .orElseThrow(() -> new ResourceNotFoundException("ViewerPage", "pageNumber", request.pageNumber()));

        byte[] cleanBytes = storageService.get(contentPage.getBucketName(), contentPage.getMinioObjectKey());
        byte[] watermarked = watermarkRenderer.applyWatermark(cleanBytes, watermarkLabel(session));

        // D9 — only successful responses reach here; every throw above logs nothing.
        viewerAccessLogService.record(session, documentVersion, contentPage, request.pageNumber(),
                request.ipAddress(), request.userAgent(), request.correlationId());

        return watermarked;
    }

    /** D7 — "{email} · #{userId} · {yyyy-MM-dd HH:mm} UTC · s{sessionId}", identifying the buyer. */
    private String watermarkLabel(ViewerSession session) {
        return "%s · #%d · %s UTC · s%d".formatted(
                session.getUser().getEmail(),
                session.getUser().getId(),
                WATERMARK_TIMESTAMP.format(Instant.now(clock).atZone(java.time.ZoneOffset.UTC)),
                session.getId());
    }

    /**
     * @return {@code null} if the session is currently active, otherwise the reason it isn't
     *         (SUPERSEDED if another session now owns the buyer+product slot; EXPIRED for every
     *         other case — including CLOSED/REVOKED, which have no dedicated D12 error code and
     *         are, from a caller's perspective, indistinguishable from a lapsed lease).
     */
    private ViewerSessionEndReason inactiveReason(ViewerSession session) {
        if (session.getEndedAt() != null) {
            return session.getEndReason() == ViewerSessionEndReason.SUPERSEDED
                    ? ViewerSessionEndReason.SUPERSEDED
                    : ViewerSessionEndReason.EXPIRED;
        }
        String activeKey = ACTIVE_KEY_PREFIX + session.getUser().getId() + ":" + session.getProduct().getId();
        String activeValue = redisTemplate.opsForValue().get(activeKey);
        if (activeValue == null) {
            return ViewerSessionEndReason.EXPIRED;
        }
        if (!activeValue.equals(String.valueOf(session.getId()))) {
            return ViewerSessionEndReason.SUPERSEDED;
        }
        return null;
    }
}
