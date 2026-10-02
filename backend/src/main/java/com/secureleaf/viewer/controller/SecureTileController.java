package com.secureleaf.viewer.controller;

import com.secureleaf.auth.service.SecureLeafUserDetails;
import com.secureleaf.common.web.CorrelationIdFilter;
import com.secureleaf.viewer.service.AsyncTileService;
import com.secureleaf.viewer.service.SecureTileService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CompletableFuture;

/**
 * REST endpoint serving one signed, single-use, watermarked DRM tile (D1, D5, D6).
 *
 * Security: {@code /api/viewer/**} is GET-only and requires authentication at the HTTP layer
 * (SecurityConfig D13) — a request with no JWT never reaches this class, it gets 401 from Spring
 * Security's entry point. Everything after that (signature, single-use, session, entitlement,
 * page range) is D6's ordered check list, all inside {@link SecureTileService}.
 */
@RestController
@RequestMapping("/api/viewer")
@RequiredArgsConstructor
public class SecureTileController {

    private final AsyncTileService asyncTileService;

    @GetMapping(value = "/tiles/{sessionId}/{pageNumber}", produces = MediaType.IMAGE_PNG_VALUE)
    public CompletableFuture<ResponseEntity<byte[]>> getTile(
            @PathVariable Long sessionId,
            @PathVariable int pageNumber,
            @RequestParam long exp,
            @RequestParam String sig,
            @RequestParam(name = "v", required = false) String variant,
            HttpServletRequest request,
            HttpServletResponse response) {

        // Phase 9, D1 — CorrelationIdFilter (the first filter in the chain) has already put an id
        // in the MDC and echoed it on the response header; this endpoint just reads it, rather
        // than generating and setting its own, so the viewer access log uses the exact same id
        // every other log line for this request does.
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);

        // D6 — never cached anywhere. Set on the servlet response NOW rather than only on the
        // ResponseEntity: with an async return, Spring Security's own Cache-Control header writer
        // would otherwise get in first and our stricter "private" directive would be dropped.
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().cachePrivate().getHeaderValue());

        // Phase 12, D3 — the checks and storage fetch run right here on the request (virtual)
        // thread and throw synchronously; only the watermark is handed to the tile-render- pool.
        // A full pool throws RenderUnavailableException here (503, D4), a slow one fails the future.
        return asyncTileService.getTile(new SecureTileService.TileRequest(
                        sessionId, pageNumber, variant, exp, sig, currentUserId(),
                        request.getRemoteAddr(), request.getHeader(HttpHeaders.USER_AGENT), correlationId))
                .thenApply(SecureTileController::toResponse);
    }

    private static ResponseEntity<byte[]> toResponse(byte[] watermarked) {
        // D6 — never let this tile response be cached anywhere (browser, proxy, CDN): each URL
        // is single-use already, but a cached copy would silently outlive that guarantee.
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header("X-Content-Type-Options", "nosniff")
                .body(watermarked);
    }

    private Long currentUserId() {
        SecureLeafUserDetails userDetails = (SecureLeafUserDetails) SecurityContextHolder
                .getContext().getAuthentication().getPrincipal();
        return userDetails.getUserId();
    }
}
