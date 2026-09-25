package com.secureleaf.viewer.controller;

import com.secureleaf.auth.service.SecureLeafUserDetails;
import com.secureleaf.viewer.service.SecureTileService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
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

import java.util.UUID;
import java.util.concurrent.TimeUnit;

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

    private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    private final SecureTileService secureTileService;

    @GetMapping(value = "/tiles/{sessionId}/{pageNumber}", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> getTile(
            @PathVariable Long sessionId,
            @PathVariable int pageNumber,
            @RequestParam long exp,
            @RequestParam String sig,
            HttpServletRequest request) {

        String correlationId = firstNonBlank(request.getHeader(CORRELATION_ID_HEADER), UUID.randomUUID().toString());

        byte[] watermarked = secureTileService.getTile(new SecureTileService.TileRequest(
                sessionId, pageNumber, exp, sig, currentUserId(),
                request.getRemoteAddr(), request.getHeader(HttpHeaders.USER_AGENT), correlationId));

        // D6 — never let this tile response be cached anywhere (browser, proxy, CDN): each URL
        // is single-use already, but a cached copy would silently outlive that guarantee.
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header("X-Content-Type-Options", "nosniff")
                .header(CORRELATION_ID_HEADER, correlationId)
                .body(watermarked);
    }

    private static String firstNonBlank(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private Long currentUserId() {
        SecureLeafUserDetails userDetails = (SecureLeafUserDetails) SecurityContextHolder
                .getContext().getAuthentication().getPrincipal();
        return userDetails.getUserId();
    }
}
