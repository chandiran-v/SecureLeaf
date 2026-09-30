package com.secureleaf.commerce.service;

import com.secureleaf.content.entity.DocumentVersion;
import com.secureleaf.content.entity.UpdatePolicy;
import com.secureleaf.content.repository.DocumentVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Phase 15, D3 — the poller that makes the entitlement migration resumable: every few seconds it
 * picks up processed FREE_UPDATE_FOR_EXISTING versions whose migration has not been stamped
 * complete and hands them to {@link EntitlementMigrationService}. A fresh upload is found by the
 * next tick; a version interrupted by a crash or restart is found the same way. One failing
 * version is logged and skipped so it cannot starve the others.
 */
@Component
@ConditionalOnProperty(name = "versioning.migration.poller.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class EntitlementMigrationJob {

    private final DocumentVersionRepository documentVersionRepository;
    private final EntitlementMigrationService migrationService;

    @Scheduled(fixedDelayString = "${versioning.migration.poll-ms:5000}")
    public void runOnce() {
        List<DocumentVersion> pending = documentVersionRepository
                .findTop20ByUpdatePolicyAndEntitlementsMigratedAtIsNullAndProcessedAtIsNotNullOrderByIdAsc(
                        UpdatePolicy.FREE_UPDATE_FOR_EXISTING);
        for (DocumentVersion version : pending) {
            try {
                migrationService.migrate(version.getId());
            } catch (RuntimeException e) {
                log.error("Entitlement migration failed for version {}; will retry on the next tick", version.getId(), e);
            }
        }
    }
}
