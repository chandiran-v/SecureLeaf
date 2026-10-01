package com.secureleaf.content.tiles;

import com.secureleaf.content.repository.ContentPageRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Phase 16, D4 — the admin-triggered backfill: renders every configured non-DESKTOP variant for
 * versions processed before it existed.
 *
 * <ul>
 *   <li><b>Idempotent / resumable</b> — the work list is "versions with fewer pages of this variant
 *       than page_count", read from the data; there is no progress table to corrupt. Run it twice,
 *       or after a crash, and it only does what is still missing.</li>
 *   <li><b>Low priority</b> — runs on the processing pool (never a request or render thread) at
 *       minimum thread priority, pausing between versions so uploads and tiles keep their CPU.</li>
 *   <li><b>One run at a time</b> — a second trigger while one is running is a no-op.</li>
 * </ul>
 * Until a version is done, the viewer serves its DESKTOP tiles (see {@code SecureTileService}).
 */
@Service
@Slf4j
public class VariantBackfillService {

    private static final int BATCH = 20;

    private final ContentPageRepository contentPageRepository;
    private final VariantVersionBackfiller versionBackfiller;
    private final TileVariantProperties tileVariants;
    private final Executor executor;
    private final long pauseBetweenVersionsMs;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public VariantBackfillService(ContentPageRepository contentPageRepository,
                                  VariantVersionBackfiller versionBackfiller,
                                  TileVariantProperties tileVariants,
                                  @Qualifier("contentProcessingExecutor") Executor executor,
                                  @Value("${content.tiles.backfill.pause-ms:200}") long pauseBetweenVersionsMs) {
        this.contentPageRepository = contentPageRepository;
        this.versionBackfiller = versionBackfiller;
        this.tileVariants = tileVariants;
        this.executor = executor;
        this.pauseBetweenVersionsMs = pauseBetweenVersionsMs;
    }

    /** @return {@code true} if a run was started, {@code false} if one is already in progress. */
    public boolean start() {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        try {
            executor.execute(() -> {
                Thread thread = Thread.currentThread();
                int original = thread.getPriority();
                thread.setPriority(Thread.MIN_PRIORITY);
                try {
                    runAll();
                } finally {
                    thread.setPriority(original);
                }
            });
        } catch (RuntimeException e) { // e.g. the pool's queue is full
            running.set(false);
            throw e;
        }
        return true;
    }

    public boolean isRunning() {
        return running.get();
    }

    /** The synchronous body (tests call it directly). @return page rows created across all versions. */
    public int runAll() {
        running.set(true);
        int created = 0;
        try {
            for (TileVariantProperties.Variant variant : tileVariants.variants()) {
                if (variant.name().equals(TileVariantProperties.DESKTOP)) continue;
                long afterId = 0;
                while (true) {
                    // A failing version is skipped for THIS run only because the cursor moves past it;
                    // the next run finds it again.
                    List<Long> batch = contentPageRepository.findVersionIdsMissingVariant(
                            variant.name(), afterId, PageRequest.of(0, BATCH));
                    if (batch.isEmpty()) break;
                    for (Long versionId : batch) {
                        afterId = versionId;
                        try {
                            created += versionBackfiller.backfill(versionId, variant.name());
                        } catch (Exception e) {
                            log.warn("Variant backfill failed for document version {} ({}): {}",
                                    versionId, variant.name(), e.getMessage());
                        }
                        pause();
                    }
                }
            }
            log.info("Variant backfill finished: {} page row(s) created", created);
            return created;
        } finally {
            running.set(false);
        }
    }

    private void pause() {
        if (pauseBetweenVersionsMs <= 0) return;
        try {
            Thread.sleep(pauseBetweenVersionsMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
