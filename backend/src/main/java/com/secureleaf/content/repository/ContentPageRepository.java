package com.secureleaf.content.repository;

import com.secureleaf.content.entity.ContentPage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ContentPageRepository extends JpaRepository<ContentPage, Long> {

    List<ContentPage> findByDocumentVersionIdOrderByPageNumber(Long documentVersionId);

    Optional<ContentPage> findByDocumentVersionIdAndPageNumber(Long documentVersionId, Integer pageNumber);

    /**
     * Delete all content pages for a document version before re-processing.
     * This makes tile generation idempotent: if the pipeline is retried from
     * CONVERT_TILES, it can safely overwrite both the MinIO objects (same key)
     * and the DB rows (delete + re-insert).
     *
     * The unique constraint {@code uq_content_pages_version_page} would cause
     * duplicate key errors on re-insert if we didn't clear first.
     */
    @Modifying
    @Query("DELETE FROM ContentPage cp WHERE cp.documentVersion.id = :versionId")
    void deleteByDocumentVersionId(@Param("versionId") Long versionId);
}
