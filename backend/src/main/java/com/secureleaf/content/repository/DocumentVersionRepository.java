package com.secureleaf.content.repository;

import com.secureleaf.content.entity.DocumentVersion;
import com.secureleaf.content.entity.UpdatePolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DocumentVersionRepository extends JpaRepository<DocumentVersion, Long> {

    Optional<DocumentVersion> findByProductIdAndVersionNumber(Long productId, Integer versionNumber);

    /** Every version of a product, newest first — the creator's version history (D7). */
    List<DocumentVersion> findByProductIdOrderByVersionNumberDesc(Long productId);

    /** The next version number for a product (D2): max + 1, counting failed and retired versions too. */
    @Query("select coalesce(max(v.versionNumber), 0) from DocumentVersion v where v.product.id = :productId")
    int maxVersionNumber(@Param("productId") Long productId);

    /** Versions whose entitlement migration still has work to do (D3), oldest first. */
    List<DocumentVersion> findTop20ByUpdatePolicyAndEntitlementsMigratedAtIsNullAndProcessedAtIsNotNullOrderByIdAsc(
            UpdatePolicy updatePolicy);

    /** Processed versions whose links haven't been extracted yet (processed before V8), oldest first. */
    List<DocumentVersion> findTop20ByLinksExtractedAtIsNullAndProcessedAtIsNotNullOrderByIdAsc();
}
