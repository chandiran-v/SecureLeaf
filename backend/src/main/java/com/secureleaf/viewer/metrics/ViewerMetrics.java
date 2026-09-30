package com.secureleaf.viewer.metrics;

import com.secureleaf.common.exception.BusinessException;
import com.secureleaf.common.exception.ErrorCode;
import com.secureleaf.common.exception.ResourceNotFoundException;
import com.secureleaf.viewer.render.RenderUnavailableException;
import com.secureleaf.viewer.repository.ViewerSessionRepository;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Phase 10 D2 — every custom meter on the viewer (tile) path, named in one place so the Grafana
 * dashboard, the tests and the load-test docs all point at the same constants.
 *
 * Micrometer names use dots; the Prometheus registry rewrites them, so
 * {@code secureleaf.tile.request} is scraped as {@code secureleaf_tile_request_seconds_*}.
 */
@Component
@Slf4j
public class ViewerMetrics {

    public static final String TILE_REQUEST = "secureleaf.tile.request";
    public static final String WATERMARK_RENDER = "secureleaf.watermark.render";
    public static final String STORAGE_FETCH = "secureleaf.storage.fetch";
    public static final String SESSIONS_ACTIVE = "secureleaf.viewer.sessions.active";
    public static final String TILE_BYTES = "secureleaf.tile.bytes";
    /** Phase 12, D5 — the render bulkhead. */
    public static final String RENDER_QUEUE_SIZE = "secureleaf.render.queue.size";
    public static final String RENDER_ACTIVE = "secureleaf.render.active";
    public static final String RENDER_WAIT = "secureleaf.render.wait";
    public static final String RENDER_REJECTED = "secureleaf.render.rejected";
    public static final String RENDER_TIMEOUT = "secureleaf.render.timeout";

    /** Values of the {@code outcome} tag on {@link #TILE_REQUEST}. */
    public static final String OUTCOME_OK = "ok";
    public static final String OUTCOME_FORBIDDEN = "forbidden";
    public static final String OUTCOME_SUPERSEDED = "superseded";
    public static final String OUTCOME_NOT_FOUND = "not_found";
    public static final String OUTCOME_ERROR = "error";
    /** Phase 12 — the render pool shed the tile (queue full) or it timed out: 503 to the client. */
    public static final String OUTCOME_UNAVAILABLE = "unavailable";

    private final MeterRegistry registry;
    private final String rendererName;
    private final DistributionSummary tileBytes;

    public ViewerMetrics(MeterRegistry registry,
                         ViewerSessionRepository viewerSessionRepository,
                         @Value("${drm.watermark.renderer:java2d}") String rendererName) {
        this.registry = registry;
        this.rendererName = rendererName;
        this.tileBytes = DistributionSummary.builder(TILE_BYTES)
                .description("Size of the watermarked tile PNG returned to the browser")
                .baseUnit("bytes")
                .register(registry);
        // Read from Postgres on every scrape (15s): an open row is a session nobody has closed
        // or swept yet. Cheap (indexed count) and correct across restarts, unlike an in-memory
        // counter. Returns NaN rather than failing the whole scrape if the DB is unreachable.
        Gauge.builder(SESSIONS_ACTIVE, () -> {
                    try {
                        return (double) viewerSessionRepository.countByEndedAtIsNull();
                    } catch (RuntimeException e) {
                        log.warn("Could not read active viewer sessions for the gauge", e);
                        return Double.NaN;
                    }
                })
                .description("Viewer sessions not yet ended (active or lapsed-but-unswept leases)")
                .register(registry);
    }

    public Timer.Sample startTileRequest() {
        return Timer.start(registry);
    }

    /** Stops the sample under the given outcome tag. The percentile histogram is what makes
     *  histogram_quantile(0.99, …) possible in Prometheus — an average alone hides the tail. */
    public void recordTileRequest(Timer.Sample sample, String outcome) {
        sample.stop(Timer.builder(TILE_REQUEST)
                .description("End-to-end tile request time inside SecureTileService")
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .register(registry));
    }

    /** Maps a failure thrown by the tile checks to its {@code outcome} tag. */
    public static String outcomeOf(RuntimeException e) {
        if (e instanceof BusinessException be) {
            if (be.getErrorCode() == ErrorCode.VIEWER_SESSION_SUPERSEDED) {
                return OUTCOME_SUPERSEDED;
            }
            // NOT_ENTITLED, SIGNED_URL_INVALID, VIEWER_SESSION_EXPIRED — all 403/410 to the client.
            return OUTCOME_FORBIDDEN;
        }
        if (e instanceof ResourceNotFoundException) {
            return OUTCOME_NOT_FOUND;
        }
        if (e instanceof RenderUnavailableException) {
            return OUTCOME_UNAVAILABLE;
        }
        return OUTCOME_ERROR;
    }

    /** Time only the watermark rendering (the CPU-bound step), tagged by renderer strategy. */
    public <T> T timeWatermark(Supplier<T> render) {
        Timer timer = Timer.builder(WATERMARK_RENDER)
                .description("Time spent inside WatermarkRenderer only")
                .tag("renderer", rendererName)
                .publishPercentileHistogram()
                .register(registry);
        return timer.record(render);
    }

    /** Time only the tile fetch from object storage (MinIO). */
    public <T> T timeStorageFetch(Supplier<T> fetch) {
        Timer timer = Timer.builder(STORAGE_FETCH)
                .description("Time fetching a clean tile from object storage")
                .publishPercentileHistogram()
                .register(registry);
        return timer.record(fetch);
    }

    /** D5 — live queue depth and busy threads, read from the pool on every scrape. */
    public void bindRenderPool(ThreadPoolExecutor pool) {
        Gauge.builder(RENDER_QUEUE_SIZE, pool, p -> p.getQueue().size())
                .description("Tiles waiting for a free render thread")
                .register(registry);
        Gauge.builder(RENDER_ACTIVE, pool, ThreadPoolExecutor::getActiveCount)
                .description("Render threads currently drawing a watermark")
                .register(registry);
    }

    /** D5 — time a tile spent queued before a render thread picked it up. */
    public void recordRenderWait(long nanos) {
        Timer.builder(RENDER_WAIT)
                .description("Time a tile waited in the render queue")
                .publishPercentileHistogram()
                .register(registry)
                .record(nanos, TimeUnit.NANOSECONDS);
    }

    /** D4 — a tile refused because the render queue was full. */
    public void recordRenderRejected() {
        registry.counter(RENDER_REJECTED).increment();
    }

    /** D3 — a tile abandoned because it exceeded {@code render.timeout-ms}. */
    public void recordRenderTimeout() {
        registry.counter(RENDER_TIMEOUT).increment();
    }

    public void recordTileBytes(int bytes) {
        tileBytes.record(bytes);
    }
}
