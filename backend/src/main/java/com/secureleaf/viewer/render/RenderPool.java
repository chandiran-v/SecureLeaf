package com.secureleaf.viewer.render;

import com.secureleaf.viewer.metrics.ViewerMetrics;
import jakarta.annotation.PreDestroy;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Phase 12, D1 — the bulkhead: a small, bounded pool of PLATFORM threads reserved for the
 * CPU-bound watermark. Everything else (Tomcat, GraphQL, storage and DB I/O) runs on other,
 * virtual threads, so a render backlog can slow tiles but never login or heartbeats.
 *
 * Platform, not virtual, threads on purpose: a virtual thread is a cheap way to WAIT (it steps
 * off its carrier while blocked on I/O). Java2D never waits — it burns a core — so a virtual
 * thread would just occupy a carrier like a platform thread, minus the bound on parallelism.
 *
 * Bounded queue + {@link ThreadPoolExecutor.AbortPolicy}: when pool and queue are full,
 * {@link #submit} throws at once ({@link RenderUnavailableException}, D4) instead of letting work
 * pile up into an ever-longer wait (an unbounded queue is a latency bomb).
 */
@Component
public class RenderPool {

    /** Suggested client back-off when we shed load or time out (D4). */
    static final long RETRY_AFTER_SECONDS = 1;

    private final ThreadPoolExecutor executor;
    private final Duration timeout;
    private final ViewerMetrics metrics;

    public RenderPool(RenderProperties properties, ViewerMetrics metrics) {
        this.metrics = metrics;
        this.timeout = Duration.ofMillis(properties.timeoutMs());
        AtomicInteger counter = new AtomicInteger();
        int size = properties.pool().size();
        this.executor = new ThreadPoolExecutor(size, size, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.pool().queueCapacity()),
                runnable -> {
                    Thread thread = new Thread(runnable, "tile-render-" + counter.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        metrics.bindRenderPool(executor);
    }

    /**
     * Queues {@code work} on the pool. The returned future completes with its result, or fails
     * with {@link RenderUnavailableException} if it outlives the timeout (D3). On timeout the task
     * is cancelled with an interrupt so an abandoned tile does not keep holding a pool thread.
     *
     * @throws RenderUnavailableException immediately when the queue is full (D4)
     */
    public <T> CompletableFuture<T> submit(Callable<T> work) {
        CompletableFuture<T> result = new CompletableFuture<>();
        long queuedAt = System.nanoTime();
        Map<String, String> callerMdc = MDC.getCopyOfContextMap();

        Future<?> handle;
        try {
            handle = executor.submit(() -> {
                metrics.recordRenderWait(System.nanoTime() - queuedAt);
                if (callerMdc != null) {
                    MDC.setContextMap(callerMdc);
                }
                try {
                    result.complete(work.call());
                } catch (Throwable t) {
                    result.completeExceptionally(t);
                } finally {
                    MDC.clear();
                }
            });
        } catch (RejectedExecutionException e) {
            metrics.recordRenderRejected();
            throw new RenderUnavailableException("Rendering is busy; try again shortly.", RETRY_AFTER_SECONDS);
        }

        result.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
        return result.whenComplete((value, failure) -> {
            if (failure != null) {
                handle.cancel(true);   // no-op if it already finished; frees the thread if it's stuck
            }
        }).exceptionally(failure -> {
            Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                    ? failure.getCause() : failure;
            if (cause instanceof TimeoutException) {
                metrics.recordRenderTimeout();
                throw new RenderUnavailableException("Rendering timed out.", RETRY_AFTER_SECONDS);
            }
            throw failure instanceof CompletionException ce ? ce : new CompletionException(cause);
        });
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
