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
import com.secureleaf.content.tiles.TileVariantProperties;
import com.secureleaf.content.watermark.WatermarkRenderer;
import com.secureleaf.viewer.cache.TileCacheKey;
import com.secureleaf.viewer.cache.TileCacheKeyFactory;
import com.secureleaf.viewer.cache.WatermarkedTileCache;
import com.secureleaf.viewer.entity.ViewerSession;
import com.secureleaf.viewer.metrics.ViewerMetrics;
import com.secureleaf.viewer.entity.ViewerSessionEndReason;
import com.secureleaf.viewer.repository.ViewerSessionRepository;
import com.secureleaf.viewer.security.TileUrlSigner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

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
@Slf4j
public class SecureTileService {

    private static final String ACTIVE_KEY_PREFIX = "viewer:active:";
    private static final String USED_SIG_KEY_PREFIX = "viewer:used-sig:";
    private static final Duration USED_SIG_TTL = Duration.ofSeconds(60);
    private static final DateTimeFormatter WATERMARK_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final ViewerSessionRepository viewerSessionRepository;
    private final EntitlementRepository entitlementRepository;
    private final ContentPageRepository contentPageRepository;
    private final StorageService storageService;
    private final WatermarkRenderer watermarkRenderer;
    private final TileUrlSigner tileUrlSigner;
    private final ViewerAccessLogService viewerAccessLogService;
    private final StringRedisTemplate redisTemplate;
    private final Clock clock;
    private final ViewerMetrics metrics;
    private final WatermarkedTileCache tileCache;
    private final TileCacheKeyFactory tileCacheKeys;

    /** {@code variant} is what the (signed) URL asked for; null means a legacy URL, i.e. DESKTOP. */
    public record TileRequest(Long sessionId, int pageNumber, String variant, long exp, String signature,
                               Long callerUserId, String ipAddress, String userAgent, String correlationId) {
    }

    /**
     * Everything the checks decided plus either the clean bytes (cache miss: handed to the render
     * pool) or the finished watermarked bytes (cache hit, Phase 13). Exactly one of
     * {@code cleanBytes} / {@code cachedTile} is non-null. The entities are detached once
     * {@link #prepareTile} returns; they are only used as foreign-key references when the access
     * log row is written, never navigated lazily.
     */
    public record PreparedTile(TileRequest request, ViewerSession session, DocumentVersion documentVersion,
                               ContentPage contentPage, byte[] cleanBytes, byte[] cachedTile,
                               String watermarkLabel, TileCacheKey cacheKey) {

        public boolean isCacheHit() {
            return cachedTile != null;
        }
    }

    /**
     * Phase 12, D3 — steps 1-7 of D6 plus the storage fetch: the part of a tile request that
     * belongs on the (virtual) request thread. Deliberately stops before the watermark, and its
     * transaction ends here, so a Postgres connection is not held while the CPU-bound render
     * waits in the queue.
     */
    @Transactional
    public PreparedTile prepareTile(TileRequest request) {
        // D6 steps 2 & 4 — the signature is computed over (sessionId|pageNumber|userId|exp), so
        // verifying it with the CALLER's own userId does double duty: it proves the URL wasn't
        // tampered with (2) AND that it was issued to this exact caller (4) in one comparison —
        // a URL signed for user A simply fails to verify when checked against user B's id.
        String requestedVariant = request.variant() == null ? TileUrlSigner.DEFAULT_VARIANT : request.variant();
        if (!tileUrlSigner.verify(request.sessionId(), request.pageNumber(), request.callerUserId(),
                requestedVariant, request.exp(), request.signature())) {
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
        // Phase 16, D4 — the variant was verified above, so it is trustworthy. If this page has no row
        // for it yet (backfill still running), serve DESKTOP: slower to load, never an error.
        ContentPage contentPage = contentPageRepository
                .findByDocumentVersionIdAndPageNumberAndVariant(documentVersion.getId(), request.pageNumber(), requestedVariant)
                .or(() -> contentPageRepository.findByDocumentVersionIdAndPageNumberAndVariant(
                        documentVersion.getId(), request.pageNumber(), TileVariantProperties.DESKTOP))
                .orElseThrow(() -> new ResourceNotFoundException("ViewerPage", "pageNumber", request.pageNumber()));
        String servedVariant = contentPage.getVariant();

        // Phase 13, D4 — only here, with every check above passed, may the cache be consulted. A hit
        // skips the storage fetch and the render; it does not skip the access-log row (VIEW-13).
        TileCacheKey cacheKey = tileCacheKeys.keyFor(session.getUser().getId(), session.getId(),
                documentVersion.getId(), request.pageNumber(), servedVariant);
        Optional<byte[]> cached = cacheLookup(cacheKey);
        if (cached.isPresent()) {
            metrics.recordTileCacheHit();
            return new PreparedTile(request, session, documentVersion, contentPage, null, cached.get(),
                    null, cacheKey);
        }
        metrics.recordTileCacheMiss();

        byte[] cleanBytes = metrics.timeStorageFetch(
                () -> storageService.get(contentPage.getBucketName(), contentPage.getMinioObjectKey()));
        return new PreparedTile(request, session, documentVersion, contentPage, cleanBytes, null,
                watermarkLabel(session), cacheKey);
    }

    /** A broken cache is a miss, never a failed request. */
    private Optional<byte[]> cacheLookup(TileCacheKey key) {
        try {
            return tileCache.get(key);
        } catch (RuntimeException e) {
            log.warn("Tile cache lookup failed; treating as a miss", e);
            return Optional.empty();
        }
    }

    /** Phase 13 — stores freshly rendered bytes; failures are logged and swallowed. */
    public void cacheRendered(PreparedTile tile, byte[] watermarked) {
        try {
            tileCache.put(tile.cacheKey(), watermarked);
        } catch (RuntimeException e) {
            log.warn("Tile cache store failed; continuing without caching", e);
        }
    }

    /** The CPU-bound step (D1). Runs on a {@code tile-render-} thread, see {@code RenderPool}. */
    public byte[] renderWatermark(PreparedTile tile) {
        return metrics.timeWatermark(() -> watermarkRenderer.applyWatermark(tile.cleanBytes(), tile.watermarkLabel()));
    }

    /**
     * D9/D6 (Phase 12) — one access-log row, written only after a successful render and never on
     * the render pool.
     */
    public void recordAccess(PreparedTile tile) {
        TileRequest request = tile.request();
        viewerAccessLogService.record(tile.session(), tile.documentVersion(), tile.contentPage(),
                request.pageNumber(), tile.contentPage().getVariant(), request.ipAddress(), request.userAgent(),
                request.correlationId());
    }

    /** Phase 13, D1 — "{email} · #{userId} · {yyyy-MM-dd} UTC · s{sessionId}". Date + session id
     * (not a minute timestamp) so the same page renders to the same bytes for a session and can be
     * cached; the access log still holds the exact per-page time, joined on the session id. */
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
