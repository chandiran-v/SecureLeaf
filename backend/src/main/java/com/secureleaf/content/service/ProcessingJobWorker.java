package com.secureleaf.content.service;

import com.secureleaf.creator.entity.JobStatus;
import com.secureleaf.creator.entity.ProcessingJob;
import com.secureleaf.creator.repository.ProcessingJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Polls the processing_jobs table every 5 seconds for QUEUED jobs.
 *
 * Why poll instead of push?
 * A message broker (Kafka, RabbitMQ) would push jobs to workers instantly.
 * But at ~10 uploads/day, a 5-second delay is imperceptible.
 * A Postgres job queue with polling requires zero additional infrastructure —
 * no broker to operate, no partition configuration, no consumer groups.
 *
 * The analogy: imagine a post office with an inbox (the processing_jobs table).
 * This worker walks to the inbox every 5 seconds and picks up any unclaimed letters
 * (QUEUED jobs), stamps them "claimed" so no other worker picks the same letter,
 * and carries them off for processing.
 *
 * The "stamps them claimed" part is the {@code FOR UPDATE SKIP LOCKED} query in
 * ProcessingJobRepository — see that class for the full explanation.
 *
 * @EnableScheduling is on SecureLeafApplication; @Scheduled requires it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProcessingJobWorker {

    /** Worker ID uniquely identifies this JVM instance (useful in multi-node deployments). */
    private static final String WORKER_ID = "worker-" + UUID.randomUUID().toString().substring(0, 8);
    private static final int POLL_BATCH_SIZE = 5;

    private final ProcessingJobRepository processingJobRepository;
    private final DocumentProcessingService documentProcessingService;

    /**
     * Runs every 5 seconds. Picks up QUEUED jobs, marks them as PROCESSING,
     * then hands each off to the async thread pool.
     *
     * fixedDelay means "wait 5s after the previous run finishes" — so if processing
     * takes 20s, the next poll starts at 25s, not 20s. This prevents a backlog
     * from triggering overlapping polls.
     *
     * @Transactional here commits the "job is now PROCESSING" update before the
     * @Async method runs — so the job's state is visible to the DB immediately,
     * even if the async thread is still waiting in the queue.
     */
    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void pollForJobs() {
        List<ProcessingJob> claimed = processingJobRepository.claimQueued(POLL_BATCH_SIZE);
        if (claimed.isEmpty()) return;

        log.debug("Claimed {} job(s) for processing", claimed.size());

        for (ProcessingJob job : claimed) {
            // Mark as PROCESSING with this worker's ID and the claim time
            job.setStatus(JobStatus.PROCESSING);
            job.setWorkerId(WORKER_ID);
            job.setClaimedAt(Instant.now());
            job.setStartedAt(Instant.now());
            processingJobRepository.save(job);

            // Hand off to the async thread pool — this returns immediately.
            // The actual work runs on a content-proc-N thread.
            documentProcessingService.processAsync(job.getId());
        }
    }
}
