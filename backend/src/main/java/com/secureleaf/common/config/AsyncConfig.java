package com.secureleaf.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Async configuration for the content processing pipeline.
 *
 * A thread pool is a group of pre-created threads that are kept alive and
 * reused for tasks. Creating a thread is expensive (~1ms + 512KB stack);
 * a pool amortises that cost over many tasks.
 *
 * The three tunable knobs:
 *
 *   coreSize   — threads always alive, even when idle (default: 4)
 *   maxSize    — maximum threads when the queue is full (default: 10)
 *   queueCapacity — tasks that can wait when all core threads are busy (default: 100)
 *
 * What happens when the queue fills?
 *   Spring's default RejectionPolicy throws RejectedExecutionException.
 *   At 10 uploads/day we will never hit this — the capacity is deliberate headroom.
 *
 * @EnableAsync is already on SecureLeafApplication.
 * @EnableScheduling is added there to support the @Scheduled job poller.
 */
@Configuration
public class AsyncConfig {

    @Value("${processing.thread-pool.core-size:4}")
    private int coreSize;

    @Value("${processing.thread-pool.max-size:10}")
    private int maxSize;

    @Value("${processing.thread-pool.queue-capacity:100}")
    private int queueCapacity;

    @Value("${processing.thread-pool.thread-name-prefix:content-proc-}")
    private String threadNamePrefix;

    /**
     * The executor used by @Async("contentProcessingExecutor") in the pipeline.
     * Named explicitly so multiple executors can coexist without ambiguity.
     */
    @Bean("contentProcessingExecutor")
    public ThreadPoolTaskExecutor contentProcessingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setWaitForTasksToCompleteOnShutdown(true);   // graceful shutdown
        executor.setAwaitTerminationSeconds(30);               // wait up to 30s for in-flight jobs
        executor.initialize();
        return executor;
    }

    /**
     * Separate, small pool for notification side effects (Redis publish, email) — Phase 4, D9.
     *
     * Why not reuse contentProcessingExecutor? Bulkheading: a slow SMTP server must never
     * occupy the threads that convert PDFs, and a burst of uploads must never delay purchase
     * receipts. Each workload gets its own pool, so one can't starve the other.
     *
     * CallerRunsPolicy: if the queue is full, the submitting thread runs the task itself —
     * slower, but a notification is never silently dropped.
     */
    @Bean("notificationExecutor")
    public ThreadPoolTaskExecutor notificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("notify-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
