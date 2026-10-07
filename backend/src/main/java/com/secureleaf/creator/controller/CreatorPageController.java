package com.secureleaf.creator.controller;

import com.secureleaf.auth.service.SecureLeafUserDetails;
import com.secureleaf.creator.service.CreatorPageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Owner preview — one clean page image of the caller's own product (REST, because it returns a
 * file). CREATOR-only at the HTTP layer ({@code GET /api/creator/**} in SecurityConfig); the
 * ownership check is in {@link CreatorPageService}. Rate-limited with the buyer tile bucket
 * ({@code RateLimitFilter}): a clean page is the most valuable thing a scraper with a stolen
 * creator login could ask for.
 */
@RestController
@RequestMapping("/api/creator/products")
@RequiredArgsConstructor
public class CreatorPageController {

    private final CreatorPageService creatorPageService;

    @GetMapping(value = "/{productId}/pages/{pageNumber}", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> page(@PathVariable Long productId, @PathVariable int pageNumber,
                                       @AuthenticationPrincipal SecureLeafUserDetails user) {
        byte[] png = creatorPageService.getOwnPage(user.getUserId(), productId, pageNumber);
        // Same as the DRM tile: a clean page must never sit in a browser or proxy cache.
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header("X-Content-Type-Options", "nosniff")
                .body(png);
    }
}
