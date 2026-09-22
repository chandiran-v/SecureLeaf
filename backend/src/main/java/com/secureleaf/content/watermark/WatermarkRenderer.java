package com.secureleaf.content.watermark;

/**
 * Strategy interface for burning a visible mark into a page image before it leaves
 * the backend.
 *
 * Two callers, two labels, one contract:
 *  - Phase 3 (free preview, {@code PreviewService}): no buyer identity to burn in yet,
 *    so it renders a static {@code "PREVIEW · SecureLeaf"} label.
 *  - Phase 5 (the paid DRM viewer): will call the same interface with the buyer's
 *    email/id baked into the label, so a leaked page tile is traceable to whoever
 *    downloaded it.
 *
 * Coding this as an interface — rather than a static utility method — from day one
 * means Phase 5 doesn't have to refactor a hardcoded implementation; it just injects a
 * different label and, if ever needed, a different implementation (e.g. a libvips-based
 * renderer for higher throughput) without touching any caller.
 */
public interface WatermarkRenderer {

    /**
     * @param imageBytes the clean page image (PNG)
     * @param label      the text to render diagonally across the page
     * @return watermarked PNG bytes
     */
    byte[] applyWatermark(byte[] imageBytes, String label);
}
