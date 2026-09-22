package com.secureleaf.content.controller;

import com.secureleaf.content.service.PreviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public REST endpoint serving watermarked free-preview page images.
 *
 * Public at the HTTP level (see SecurityConfig — GET /api/products/*&#47;preview/**
 * is permitAll, same pattern as /graphql), because there's no buyer identity here to
 * check against — anyone, logged in or not, can view a product's free preview pages.
 * All access control is the page-range + LIVE-status check inside {@link PreviewService}.
 *
 * This endpoint does real CPU work (watermark rendering) per request and is public, so
 * it's a DoS surface. Phase 3 bounds the blast radius with the strict page-range check;
 * real rate limiting is deferred to MVP-2 (see phase-03-marketplace.md, out of scope).
 */
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class PreviewController {

    private final PreviewService previewService;

    @GetMapping(value = "/{productId}/preview/{pageNumber}", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> getPreviewPage(
            @PathVariable Long productId,
            @PathVariable int pageNumber) {
        byte[] watermarked = previewService.getPreviewPage(productId, pageNumber);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .body(watermarked);
    }
}
