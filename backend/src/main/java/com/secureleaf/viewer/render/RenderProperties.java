package com.secureleaf.viewer.render;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code render.*} settings (Phase 12, D1/D3) — the bulkhead's size, its waiting room and the
 * per-tile deadline. Every number is configuration so an operator can retune without a redeploy.
 *
 * @param pool      the dedicated watermark pool
 * @param timeoutMs longest a tile may take from "submitted" to "rendered" (queue wait + render)
 *                  before the client gets 503 (D3)
 */
@ConfigurationProperties(prefix = "render")
public record RenderProperties(Pool pool, Long timeoutMs) {

    /**
     * @param size          platform threads; defaults to the CPU count, because rendering is
     *                      CPU-bound (more threads than cores only adds context switching)
     * @param queueCapacity how many tiles may wait for a free thread; bounded on purpose (D1)
     */
    public record Pool(Integer size, Integer queueCapacity) {
        public Pool {
            if (size == null || size < 1) {
                size = Runtime.getRuntime().availableProcessors();
            }
            if (queueCapacity == null || queueCapacity < 1) {
                queueCapacity = 200;
            }
        }
    }

    public RenderProperties {
        if (pool == null) {
            pool = new Pool(null, null);
        }
        if (timeoutMs == null || timeoutMs < 1) {
            timeoutMs = 5000L;
        }
    }
}
