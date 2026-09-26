package com.secureleaf.creator.repository;

import com.secureleaf.creator.entity.ProcessingJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProcessingJobRepository extends JpaRepository<ProcessingJob, Long> {

    /**
     * The "current" job for a product — a re-upload (DocumentUploadController) marks every
     * prior job FAILED and inserts a fresh row, so the highest id is always the attempt that
     * matters right now, regardless of how many times the product has been re-uploaded.
     */
    Optional<ProcessingJob> findFirstByProductIdOrderByIdDesc(Long productId);

    /** Dashboard batch loader (Phase 6, D4) — one query for a whole page of products. */
    List<ProcessingJob> findByProductIdInOrderByIdDesc(Collection<Long> productIds);

    /**
     * Atomically claims up to {@code limit} QUEUED jobs for processing.
     *
     * {@code FOR UPDATE} takes a row-level lock on each selected row inside this
     * transaction, preventing other transactions from modifying those rows until
     * this transaction commits.
     *
     * {@code SKIP LOCKED} makes each worker skip rows that are already locked by
     * another worker, instead of waiting. This turns the table into a non-blocking
     * queue where 10 workers can each pull different jobs simultaneously without
     * blocking each other.
     *
     * Without SKIP LOCKED: Worker 2 blocks waiting for Worker 1 to release the lock.
     * Without FOR UPDATE: Two workers could read the same QUEUED row and process
     *   the same job twice (duplicate work, duplicate tiles).
     *
     * The partial index {@code idx_processing_jobs_status_queued} on the
     * {@code (status, queued_at ASC) WHERE status = 'QUEUED'} makes this
     * query fast even with thousands of completed jobs in the table.
     */
    @Query(value = """
            SELECT * FROM processing_jobs
            WHERE status = 'QUEUED'
            ORDER BY queued_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<ProcessingJob> claimQueued(@Param("limit") int limit);
}
