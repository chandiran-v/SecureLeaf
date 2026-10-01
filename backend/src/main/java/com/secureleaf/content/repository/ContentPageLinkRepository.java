package com.secureleaf.content.repository;

import com.secureleaf.content.entity.ContentPageLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ContentPageLinkRepository extends JpaRepository<ContentPageLink, Long> {

    /**
     * The links on one page of one document version, in reading (insertion) order. Links are stored
     * once per page, on the DESKTOP row, as ratios of the page, so they fit every variant (Phase 16).
     */
    @Query("""
            SELECT l FROM ContentPageLink l
            WHERE l.contentPage.documentVersion.id = :versionId AND l.contentPage.pageNumber = :pageNumber
              AND l.contentPage.variant = 'DESKTOP'
            ORDER BY l.id
            """)
    List<ContentPageLink> findForPage(@Param("versionId") Long versionId, @Param("pageNumber") int pageNumber);
}
