package com.secureleaf.viewer.service;

import com.secureleaf.auth.service.JwtService;
import com.secureleaf.commerce.entity.Entitlement;
import com.secureleaf.commerce.entity.EntitlementStatus;
import com.secureleaf.commerce.repository.EntitlementRepository;
import com.secureleaf.common.exception.BusinessException;
import com.secureleaf.common.exception.ErrorCode;
import com.secureleaf.viewer.DrmProperties;
import com.secureleaf.viewer.dto.ViewerHeartbeatDto;
import com.secureleaf.viewer.dto.ViewerSessionDto;
import com.secureleaf.viewer.dto.SignedPageUrlDto;
import com.secureleaf.viewer.entity.ViewerSession;
import com.secureleaf.viewer.entity.ViewerSessionEndReason;
import com.secureleaf.viewer.repository.ViewerSessionRepository;
import com.secureleaf.viewer.security.TileUrlSigner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Owns the lifecycle of a {@link ViewerSession}: start, heartbeat, end, supersede, and the
 * sweeper that reaps lapsed leases (D3, D4).
 *
 * REDIS AS THE SOURCE OF TRUTH FOR "WHO IS ACTIVE RIGHT NOW"
 * Postgres ({@code viewer_sessions}) is the durable, queryable audit trail — every session ever
 * opened. Redis's {@code viewer:active:{userId}:{productId}} key is a separate, much narrower
 * fact: "which session, if any, currently owns this buyer+product slot". Two data stores because
 * they answer two different questions at two different speeds: every heartbeat (every 15s, per
 * open tab) only needs to touch the fast, TTL-native store; Postgres is written to only at the
 * state transitions (start / supersede / end / sweep).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ViewerSessionService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String ACTIVE_KEY_PREFIX = "viewer:active:";

    /**
     * D3 — {@code SET key value EX ttl GET}: atomically installs this session as the active one
     * and returns whoever held the slot before (or nil if nobody did). "Last writer wins" — the
     * opposite of the single-use tile signature's {@code SET NX} (D5) — because VIEW-10 requires
     * a *newer* login to always evict an older one, not to be silently rejected by it.
     */
    private static final DefaultRedisScript<String> ACTIVATE_SESSION_SCRIPT = new DefaultRedisScript<>(
            "return redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2], 'GET')", String.class);

    /**
     * D4 — atomic compare-and-refresh. Plain {@code GET} then {@code EXPIRE} from application
     * code would race: another session's {@code startViewerSession} could overwrite the key
     * between our GET and EXPIRE, and we'd refresh a TTL that no longer belongs to us. Returns
     * 1 = refreshed (still ours), 2 = someone else now owns the slot, 0 = the key is gone.
     */
    private static final DefaultRedisScript<Long> HEARTBEAT_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            if current == false then
                return 0
            elseif current == ARGV[1] then
                redis.call('EXPIRE', KEYS[1], ARGV[2])
                return 1
            else
                return 2
            end
            """, Long.class);

    /** Deletes the active-session key only if it still points at us (avoids evicting a newer session). */
    private static final DefaultRedisScript<Long> RELEASE_SESSION_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            if current == ARGV[1] then
                redis.call('DEL', KEYS[1])
                return 1
            else
                return 0
            end
            """, Long.class);

    private final ViewerSessionRepository viewerSessionRepository;
    private final EntitlementRepository entitlementRepository;
    private final StringRedisTemplate redisTemplate;
    private final JwtService jwtService;
    private final DrmProperties drmProperties;
    private final Clock clock;
    private final TileUrlSigner tileUrlSigner;

    /** D1/D2 — starts (or takes over) the one viewer session for this buyer+product. */
    @Transactional
    public ViewerSessionDto startViewerSession(Long buyerId, Long productId, String deviceFingerprint, String ipAddress) {
        Entitlement entitlement = entitlementRepository
                .findByBuyerIdAndProductIdAndStatus(buyerId, productId, EntitlementStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_ENTITLED,
                        "You do not have an active entitlement for this product."));

        String rawToken = generateSessionToken();

        ViewerSession session = new ViewerSession();
        session.setUser(entitlement.getBuyer());
        session.setProduct(entitlement.getProduct());
        session.setEntitlement(entitlement);
        session.setSessionTokenHash(jwtService.hashToken(rawToken));
        session.setDeviceFingerprint(deviceFingerprint);
        session.setIpAddress(ipAddress);
        session.setLastHeartbeatAt(Instant.now(clock));
        session = viewerSessionRepository.save(session);

        int leaseSeconds = drmProperties.session().leaseSeconds();
        String previousSessionId = redisTemplate.execute(ACTIVATE_SESSION_SCRIPT,
                List.of(activeKey(buyerId, productId)),
                String.valueOf(session.getId()), String.valueOf(leaseSeconds));

        if (previousSessionId != null && !previousSessionId.equals(String.valueOf(session.getId()))) {
            endSession(Long.valueOf(previousSessionId), ViewerSessionEndReason.SUPERSEDED);
        }

        Integer pageCount = entitlement.getDocumentVersion() != null
                ? entitlement.getDocumentVersion().getPageCount()
                : null;

        return new ViewerSessionDto(
                session.getId(),
                rawToken,
                productId,
                pageCount,
                drmProperties.session().heartbeatIntervalSeconds(),
                Instant.now(clock).plusSeconds(leaseSeconds).atOffset(ZoneOffset.UTC));
    }

    /** D4 — the client's lease renewal, every {@code heartbeat-interval-seconds}. */
    @Transactional
    public ViewerHeartbeatDto heartbeat(String sessionToken) {
        Optional<ViewerSession> maybeSession = viewerSessionRepository.findBySessionTokenHash(jwtService.hashToken(sessionToken));
        if (maybeSession.isEmpty()) {
            return new ViewerHeartbeatDto("EXPIRED", Instant.now(clock).atOffset(ZoneOffset.UTC));
        }
        ViewerSession session = maybeSession.get();

        // Already ended (e.g. a later startViewerSession superseded it) — no Redis round trip needed.
        if (session.getEndedAt() != null) {
            String status = session.getEndReason() == ViewerSessionEndReason.SUPERSEDED ? "SUPERSEDED" : "EXPIRED";
            return new ViewerHeartbeatDto(status, session.getEndedAt().atOffset(ZoneOffset.UTC));
        }

        int leaseSeconds = drmProperties.session().leaseSeconds();
        Long result = redisTemplate.execute(HEARTBEAT_SCRIPT,
                List.of(activeKey(session.getUser().getId(), session.getProduct().getId())),
                String.valueOf(session.getId()), String.valueOf(leaseSeconds));

        if (result != null && result == 1L) {
            session.setLastHeartbeatAt(Instant.now(clock));
            viewerSessionRepository.save(session);
            return new ViewerHeartbeatDto("ACTIVE", Instant.now(clock).plusSeconds(leaseSeconds).atOffset(ZoneOffset.UTC));
        }
        // 2 (another session owns the slot) or 0 (key gone): report the fact, but don't write
        // ended_at here — that belongs to startViewerSession's supersede step or the sweeper
        // (D4), so both paths only ever end a row exactly once, for one clear reason.
        String status = (result != null && result == 2L) ? "SUPERSEDED" : "EXPIRED";
        return new ViewerHeartbeatDto(status, Instant.now(clock).atOffset(ZoneOffset.UTC));
    }

    /** Explicit close — the buyer navigated away or closed the tab. */
    @Transactional
    public boolean endViewerSession(String sessionToken) {
        Optional<ViewerSession> maybeSession = viewerSessionRepository.findBySessionTokenHash(jwtService.hashToken(sessionToken));
        if (maybeSession.isEmpty()) {
            return false;
        }
        ViewerSession session = maybeSession.get();
        if (session.getEndedAt() == null) {
            session.setEndedAt(Instant.now(clock));
            session.setEndReason(ViewerSessionEndReason.CLOSED);
            viewerSessionRepository.save(session);
        }
        redisTemplate.execute(RELEASE_SESSION_SCRIPT,
                List.of(activeKey(session.getUser().getId(), session.getProduct().getId())),
                String.valueOf(session.getId()));
        return true;
    }

    /**
     * D4 — every 60s, ends any DB row whose lease has lapsed. This is a safety net: heartbeat
     * already reports EXPIRED to the client the moment Redis notices, but nobody may ever call
     * heartbeat again on a session the buyer simply abandoned (closed the laptop). Without this,
     * that row would stay open forever, making "how many people are viewing this right now"
     * queries wrong indefinitely instead of within 60-105s.
     */
    @Scheduled(fixedRate = 60_000)
    @Transactional
    public void sweepExpiredLeases() {
        int leaseSeconds = drmProperties.session().leaseSeconds();
        Instant cutoff = Instant.now(clock).minusSeconds(leaseSeconds);
        List<ViewerSession> expired = viewerSessionRepository.findExpiredLeases(cutoff);
        if (expired.isEmpty()) {
            return;
        }
        Instant now = Instant.now(clock);
        for (ViewerSession session : expired) {
            session.setEndedAt(now);
            session.setEndReason(ViewerSessionEndReason.EXPIRED);
        }
        viewerSessionRepository.saveAll(expired);
        log.debug("Sweeper ended {} lapsed viewer session(s)", expired.size());
    }

    /**
     * D5 — mints one signed, single-use tile URL. Ownership is checked here (not left to the
     * REST endpoint's D6 checks alone) so a buyer never even receives a URL for someone else's
     * session. Read-only and transactional so {@code session.getUser()} (LAZY) can be read
     * safely — see the bug this fixed in the learning note's Gotchas table.
     */
    @Transactional(readOnly = true)
    public SignedPageUrlDto signPageUrl(Long callerUserId, String sessionToken, int pageNumber) {
        ViewerSession session = viewerSessionRepository.findBySessionTokenHash(jwtService.hashToken(sessionToken))
                .orElseThrow(() -> new BusinessException(ErrorCode.SIGNED_URL_INVALID, "Unknown viewer session."));
        if (!session.getUser().getId().equals(callerUserId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "This viewer session belongs to another user.");
        }
        TileUrlSigner.Signature signature = tileUrlSigner.sign(session.getId(), pageNumber, callerUserId);
        String url = "/api/viewer/tiles/%d/%d?exp=%d&sig=%s"
                .formatted(session.getId(), pageNumber, signature.expiresAtEpochSeconds(), signature.value());
        return new SignedPageUrlDto(url, Instant.ofEpochSecond(signature.expiresAtEpochSeconds()).atOffset(ZoneOffset.UTC));
    }

    /** Used both by the D3 supersede step (start) and directly by tests/other services if needed. */
    @Transactional
    public void endSession(Long sessionId, ViewerSessionEndReason reason) {
        viewerSessionRepository.findById(sessionId).ifPresent(session -> {
            if (session.getEndedAt() == null) {
                session.setEndedAt(Instant.now(clock));
                session.setEndReason(reason);
                viewerSessionRepository.save(session);
            }
        });
    }

    /** D2 — 256 bits from SecureRandom, Base64URL-encoded. Only its SHA-256 hash is ever stored. */
    private static String generateSessionToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String activeKey(Long userId, Long productId) {
        return ACTIVE_KEY_PREFIX + userId + ":" + productId;
    }
}
