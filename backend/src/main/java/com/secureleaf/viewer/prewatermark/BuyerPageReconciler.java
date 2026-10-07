package com.secureleaf.viewer.prewatermark;

import com.secureleaf.common.storage.StorageService;
import com.secureleaf.viewer.entity.BuyerPage;
import com.secureleaf.viewer.repository.BuyerPageRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Purchase-time watermarking: keeps {@code buyer_pages} in step with the entitlements, so a page
 * view can serve a stored, already-watermarked page instead of rendering one.
 *
 * <p>A RECONCILIATION LOOP, not event hooks. Entitlements change in several places (a purchase, a
 * free-update migration to a new version, a refund, the load-test seeder, existing buyers on the
 * day this shipped). Rather than hook each one and hope nobody adds a fifth, every run compares the
 * desired state ("every ACTIVE entitlement has every page of its version") with the actual state
 * (the {@code buyer_pages} rows) and fixes the difference: generate what is missing, delete what is
 * stale. Restarts, crashes and missed events all heal on the next run. Same idea as a Kubernetes
 * controller, and as {@code VariantBackfillService} here.
 *
 * <p>ITS OWN PLATFORM THREAD. Watermarking is CPU-bound. This app runs requests and {@code @Scheduled}
 * tasks on virtual threads, which share one carrier thread per core; on a 0.1-CPU host that is ONE
 * carrier, and a CPU loop on a virtual thread would starve every request (the Phase 12 lesson). So
 * the timer only calls {@link #start()}, and the work runs on a dedicated single platform thread.
 * One run at a time; a run that is still going makes the next trigger a no-op.
 */
@Service
@Slf4j
public class BuyerPageReconciler {

    private static final int CLEANUP_BATCH = 100;
    private static final int MAX_CLEANUP_BATCHES_PER_RUN = 10;

    private final BuyerPageRepository buyerPageRepository;
    private final BuyerPageWatermarker watermarker;
    private final StorageService storageService;
    private final String watermarkVersion;
    private final int entitlementsPerRun;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "prewatermark-1");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    /** Cursor over entitlement ids, kept between runs so one entitlement that keeps failing cannot
     *  hold up the ones after it; it wraps back to 0 when it reaches the end. */
    private volatile long afterId = 0;

    public record Result(int pagesDeleted, int pagesGenerated) {}

    public BuyerPageReconciler(BuyerPageRepository buyerPageRepository,
                               BuyerPageWatermarker watermarker,
                               StorageService storageService,
                               @Value("${drm.prewatermark.version:1}") String watermarkVersion,
                               @Value("${drm.prewatermark.entitlements-per-run:5}") int entitlementsPerRun) {
        this.buyerPageRepository = buyerPageRepository;
        this.watermarker = watermarker;
        this.storageService = storageService;
        this.watermarkVersion = watermarkVersion;
        this.entitlementsPerRun = entitlementsPerRun;
    }

    /** Timer entry point: hands one run to the worker thread. @return false if a run is in progress. */
    public boolean start() {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        try {
            worker.execute(() -> {
                try {
                    reconcile();
                } catch (RuntimeException e) {
                    log.warn("Pre-watermark run failed; the next run retries: {}", e.getMessage());
                } finally {
                    running.set(false);
                }
            });
            return true;
        } catch (RejectedExecutionException e) {   // shutting down
            running.set(false);
            return false;
        }
    }

    /** One synchronous run (tests call this directly). */
    public Result runOnce() {
        if (!running.compareAndSet(false, true)) {
            return new Result(0, 0);
        }
        try {
            return reconcile();
        } finally {
            running.set(false);
        }
    }

    private Result reconcile() {
        int deleted = deleteStale();
        int generated = 0;
        List<Long> batch = buyerPageRepository.findEntitlementIdsMissingPages(watermarkVersion, afterId, entitlementsPerRun);
        if (batch.isEmpty() && afterId > 0) {
            afterId = 0;   // reached the end: start over from the lowest id next time
            batch = buyerPageRepository.findEntitlementIdsMissingPages(watermarkVersion, 0, entitlementsPerRun);
        }
        for (Long entitlementId : batch) {
            afterId = entitlementId;
            try {
                generated += watermarker.generate(entitlementId);
            } catch (RuntimeException e) {
                // Skipped for this run only; the page view keeps falling back to live watermarking.
                log.warn("Pre-watermarking entitlement {} failed: {}", entitlementId, e.getMessage());
            }
        }
        return new Result(deleted, generated);
    }

    /** Deletes rows (and objects) of refunded/revoked entitlements, of versions an entitlement has
     *  moved away from, and of an old watermark version. Bounded per run. */
    private int deleteStale() {
        int deleted = 0;
        for (int i = 0; i < MAX_CLEANUP_BATCHES_PER_RUN; i++) {
            List<BuyerPage> stale = buyerPageRepository.findStale(watermarkVersion, CLEANUP_BATCH);
            if (stale.isEmpty()) break;
            for (BuyerPage page : stale) {
                try {
                    storageService.delete(page.getBucketName(), page.getObjectKey());
                } catch (RuntimeException e) {
                    // The row goes anyway: an orphan object costs a little storage, while a row
                    // whose delete keeps failing would be retried forever.
                    log.warn("Could not delete pre-watermarked object {}/{}: {}",
                            page.getBucketName(), page.getObjectKey(), e.getMessage());
                }
            }
            buyerPageRepository.deleteAllInBatch(stale);
            deleted += stale.size();
        }
        if (deleted > 0) {
            log.info("Deleted {} stale pre-watermarked page(s)", deleted);
        }
        return deleted;
    }

    @PreDestroy
    void shutdown() {
        worker.shutdownNow();
    }
}
