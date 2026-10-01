package com.secureleaf.commerce.service;

import com.secureleaf.commerce.repository.EntitlementRepository;
import com.secureleaf.content.entity.DocumentVersion;
import com.secureleaf.content.entity.UpdatePolicy;
import com.secureleaf.content.repository.DocumentVersionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

/**
 * Phase 15, D3 — the batch job behind {@code FREE_UPDATE_FOR_EXISTING}: moves every ACTIVE
 * entitlement of a product onto the version that just became current.
 *
 * THREE PROPERTIES, AND WHERE EACH COMES FROM
 *  - Chunked: 500 rows per transaction (not one giant UPDATE), so a product with 100k buyers never
 *    holds a long lock or a huge transaction. Each chunk commits on its own.
 *  - Idempotent: a chunk selects only entitlements NOT already on the target version, and the
 *    UPDATE is a plain "set version = X". Running it twice moves nothing the second time.
 *  - Resumable: the checkpoint is the data itself ("which ACTIVE entitlements are not on X yet")
 *    plus {@code entitlements_migrated_at} on the version. A crash between chunks leaves some rows
 *    moved and the version still un-stamped; the poller simply runs again and finishes the rest.
 *    No cursor or offset to lose.
 *
 * Uses {@link TransactionTemplate} rather than {@code @Transactional}: the per-chunk commit has to
 * happen inside a loop in one method, and a self-invoked {@code @Transactional} method would skip
 * the Spring proxy (and therefore the transaction).
 */
@Service
@Slf4j
public class EntitlementMigrationService {

    private final EntitlementRepository entitlementRepository;
    private final DocumentVersionRepository documentVersionRepository;
    private final TransactionTemplate tx;
    private final int chunkSize;

    public EntitlementMigrationService(EntitlementRepository entitlementRepository,
                                       DocumentVersionRepository documentVersionRepository,
                                       PlatformTransactionManager transactionManager,
                                       @Value("${versioning.migration.chunk-size:500}") int chunkSize) {
        this.entitlementRepository = entitlementRepository;
        this.documentVersionRepository = documentVersionRepository;
        this.tx = new TransactionTemplate(transactionManager);
        this.chunkSize = chunkSize;
    }

    /** Runs to completion. Returns how many entitlements this call moved. */
    public int migrate(Long versionId) {
        return migrate(versionId, Integer.MAX_VALUE);
    }

    /**
     * Runs at most {@code maxChunks} chunks, then stops WITHOUT stamping the version complete — the
     * test hook that simulates a crash between chunks (production callers use {@link #migrate(Long)}).
     */
    public int migrate(Long versionId, int maxChunks) {
        Plan plan = tx.execute(status -> plan(versionId));
        if (plan == null) return 0;

        int total = 0;
        int chunks = 0;
        while (chunks < maxChunks) {
            Integer moved = tx.execute(status -> {
                List<Long> ids = entitlementRepository.findChunkToMigrate(plan.productId(), versionId, chunkSize);
                if (ids.isEmpty()) return 0;
                return entitlementRepository.moveToVersion(ids, versionId);
            });
            if (moved == null || moved == 0) {
                complete(versionId);
                log.info("Entitlement migration complete: version={}, product={}, moved={} in this run",
                        versionId, plan.productId(), total);
                return total;
            }
            total += moved;
            chunks++;
            log.info("Entitlement migration progress: version={}, product={}, chunk={}, moved so far={}",
                    versionId, plan.productId(), chunks, total);
        }
        log.warn("Entitlement migration paused after {} chunk(s): version={}, moved={}; it resumes on the next run",
                chunks, versionId, total);
        return total;
    }

    /** Null = nothing to do (wrong policy, unprocessed, already done, or superseded). */
    private Plan plan(Long versionId) {
        DocumentVersion version = documentVersionRepository.findById(versionId).orElse(null);
        if (version == null || version.getUpdatePolicy() != UpdatePolicy.FREE_UPDATE_FOR_EXISTING
                || version.getProcessedAt() == null || version.getEntitlementsMigratedAt() != null) {
            return null;
        }
        DocumentVersion current = version.getProduct().getCurrentDocumentVersion();
        if (current == null || !current.getId().equals(version.getId())) {
            // A newer version took over (or this one was retired) before its migration ran: pushing
            // buyers onto a non-current version would move them BACKWARDS relative to the pointer.
            version.setEntitlementsMigratedAt(Instant.now());
            documentVersionRepository.save(version);
            log.info("Entitlement migration skipped: version={} is no longer current", versionId);
            return null;
        }
        return new Plan(version.getProduct().getId());
    }

    private void complete(Long versionId) {
        tx.executeWithoutResult(status -> documentVersionRepository.findById(versionId).ifPresent(v -> {
            v.setEntitlementsMigratedAt(Instant.now());
            documentVersionRepository.save(v);
        }));
    }

    private record Plan(Long productId) {}
}
