package com.secureleaf.viewer.service;

import com.secureleaf.viewer.metrics.ViewerMetrics;
import com.secureleaf.viewer.render.RenderPool;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Phase 12, D3 — the tile pipeline split across threads:
 * <ol>
 *   <li>request (virtual) thread: D6 checks + storage fetch ({@link SecureTileService#prepareTile});</li>
 *   <li>{@code tile-render-} platform thread: the watermark only ({@link RenderPool});</li>
 *   <li>a virtual thread again: the access-log INSERT (D6 — never on the render pool).</li>
 * </ol>
 * This is a separate bean (not more methods on SecureTileService) so that
 * {@code prepareTile}'s {@code @Transactional} is applied through the Spring proxy.
 */
@Service
@RequiredArgsConstructor
public class AsyncTileService {

    private final SecureTileService secureTileService;
    private final RenderPool renderPool;
    private final ViewerMetrics metrics;

    /** Virtual thread per task: the access-log write is blocking JDBC, exactly what they suit. */
    private final ExecutorService ioExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Failures of the checks (403/404/409/410) throw synchronously, as before; a full render queue
     * throws {@code RenderUnavailableException} synchronously; a timeout fails the future.
     */
    public CompletableFuture<byte[]> getTile(SecureTileService.TileRequest request) {
        // Phase 10 D2 — the timer covers the whole request, tagged by how it ended.
        Timer.Sample sample = metrics.startTileRequest();
        try {
            SecureTileService.PreparedTile prepared = secureTileService.prepareTile(request);
            return renderPool.submit(() -> secureTileService.renderWatermark(prepared))
                    .thenApplyAsync(watermarked -> {
                        secureTileService.recordAccess(prepared);
                        return watermarked;
                    }, ioExecutor)
                    .whenComplete((tile, failure) -> {
                        if (failure == null) {
                            metrics.recordTileBytes(tile.length);
                            metrics.recordTileRequest(sample, ViewerMetrics.OUTCOME_OK);
                        } else {
                            metrics.recordTileRequest(sample, ViewerMetrics.outcomeOf(unwrap(failure)));
                        }
                    });
        } catch (RuntimeException e) {
            metrics.recordTileRequest(sample, ViewerMetrics.outcomeOf(e));
            throw e;
        }
    }

    private static RuntimeException unwrap(Throwable failure) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause() : failure;
        return cause instanceof RuntimeException re ? re : new IllegalStateException(cause);
    }

    @PreDestroy
    void shutdown() {
        ioExecutor.shutdown();
    }
}
