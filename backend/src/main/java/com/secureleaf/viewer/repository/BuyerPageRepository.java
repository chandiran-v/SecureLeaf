package com.secureleaf.viewer.repository;

import com.secureleaf.viewer.entity.BuyerPage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BuyerPageRepository extends JpaRepository<BuyerPage, Long> {

    /** The view path: this buyer's ready-made copy of this page, if it has been generated. */
    Optional<BuyerPage> findByEntitlementIdAndDocumentVersionIdAndPageNumberAndVariantAndWatermarkVersion(
            Long entitlementId, Long documentVersionId, Integer pageNumber, String variant, String watermarkVersion);

    /** What one entitlement already has, so generation only renders what is missing. */
    List<BuyerPage> findByEntitlementIdAndDocumentVersionIdAndWatermarkVersion(
            Long entitlementId, Long documentVersionId, String watermarkVersion);

    /**
     * The reconciler's work list, derived from the data (no progress table to corrupt): ACTIVE
     * entitlements on a processed, non-retired version that have fewer current-watermark pages
     * than the version has page rows (all variants). Ordered by id with a cursor, so one failing
     * entitlement cannot block the ones after it.
     */
    @Query(value = """
            SELECT e.id FROM entitlements e
            JOIN document_versions dv ON dv.id = e.document_version_id
            WHERE e.status = 'ACTIVE' AND e.id > :afterId
              AND dv.processed_at IS NOT NULL AND dv.retired_at IS NULL
              AND (SELECT COUNT(*) FROM buyer_pages bp
                   WHERE bp.entitlement_id = e.id AND bp.document_version_id = e.document_version_id
                     AND bp.watermark_version = :watermarkVersion)
                < (SELECT COUNT(*) FROM content_pages cp WHERE cp.document_version_id = e.document_version_id)
            ORDER BY e.id
            LIMIT :limit
            """, nativeQuery = true)
    List<Long> findEntitlementIdsMissingPages(@Param("watermarkVersion") String watermarkVersion,
                                              @Param("afterId") long afterId,
                                              @Param("limit") int limit);

    /**
     * Rows nobody may be served any more: the entitlement was refunded/revoked, moved to another
     * version (a free update), or the watermark design changed. Their objects are deleted too.
     */
    @Query(value = """
            SELECT bp.* FROM buyer_pages bp
            JOIN entitlements e ON e.id = bp.entitlement_id
            WHERE e.status <> 'ACTIVE'
               OR bp.document_version_id <> e.document_version_id
               OR bp.watermark_version <> :watermarkVersion
            ORDER BY bp.id
            LIMIT :limit
            """, nativeQuery = true)
    List<BuyerPage> findStale(@Param("watermarkVersion") String watermarkVersion, @Param("limit") int limit);
}
