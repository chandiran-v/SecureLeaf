package com.secureleaf.viewer.prewatermark;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The timer for {@link BuyerPageReconciler}. It only hands a run to the reconciler's own thread, so
 * a slow run never holds a scheduler thread. Disabled in tests ({@code drm.prewatermark.enabled=false})
 * so it cannot race tests that drive {@link BuyerPageReconciler#runOnce()} themselves.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "drm.prewatermark.enabled", havingValue = "true", matchIfMissing = true)
public class BuyerPageReconcilerJob {

    private final BuyerPageReconciler reconciler;

    @Scheduled(initialDelayString = "${drm.prewatermark.initial-delay-ms:20000}",
               fixedDelayString = "${drm.prewatermark.interval-ms:15000}")
    public void run() {
        reconciler.start();
    }
}
