package com.secureleaf.content.repository;

import com.secureleaf.content.entity.ContentPage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ContentPageRepository extends JpaRepository<ContentPage, Long> {

    /** Every row of the version, all variants (retire deletes every object; backfill reads them). */
    List<ContentPage> findByDocumentVersionIdOrderByPageNumber(Long documentVersionId);

    /** One row per page of one variant — what link extraction and page counts work from. */
    List<ContentPage> findByDocumentVersionIdAndVariantOrderByPageNumber(Long documentVersionId, String variant);

    Optional<ContentPage> findByDocumentVersionIdAndPageNumberAndVariant(
            Long documentVersionId, Integer pageNumber, String variant);

    /**
     * Phase 16, D4 — processed, live versions that still lack some pages of {@code variant}, oldest
     * first and after {@code afterId}. Derived from the data itself, so the backfill needs no
     * checkpoint: a stopped run simply finds the remaining ones next time.
     */
    @Query("""
            SELECT dv.id FROM DocumentVersion dv
            WHERE dv.id > :afterId AND dv.processedAt IS NOT NULL AND dv.retiredAt IS NULL
              AND (SELECT COUNT(cp) FROM ContentPage cp
                   WHERE cp.documentVersion = dv AND cp.variant = :variant) < dv.pageCount
            ORDER BY dv.id
            """)
    List<Long> findVersionIdsMissingVariant(@Param("variant") String variant, @Param("afterId") Long afterId,
                                            org.springframework.data.domain.Pageable limit);

    /**
     * Delete all content pages for a document version before re-processing.
     * This makes tile generation idempotent: if the pipeline is retried from
     * CONVERT_TILES, it can safely overwrite both the MinIO objects (same key)
     * and the DB rows (delete + re-insert).
     *
     * The unique constraint {@code uq_content_pages_version_page_variant} would cause
     * duplicate key errors on re-insert if we didn't clear first.
     */
    @Modifying
    @Query("DELETE FROM ContentPage cp WHERE cp.documentVersion.id = :versionId")
    void deleteByDocumentVersionId(@Param("versionId") Long versionId);
}
