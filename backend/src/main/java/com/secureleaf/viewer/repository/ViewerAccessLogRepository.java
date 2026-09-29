package com.secureleaf.viewer.repository;

import com.secureleaf.viewer.entity.ViewerAccessLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ViewerAccessLogRepository extends JpaRepository<ViewerAccessLog, Long> {

    /** Phase 11 D6 — users with MORE than {@code minTiles} successful tiles since {@code since}, busiest first. */
    @Query("""
            SELECT l.user.id AS userId, COUNT(l) AS tileCount, MAX(l.viewedAt) AS lastTileAt
            FROM ViewerAccessLog l
            WHERE l.viewedAt >= :since
            GROUP BY l.user.id
            HAVING COUNT(l) > :minTiles
            ORDER BY COUNT(l) DESC
            """)
    List<HeavyViewer> findHeavyViewers(@Param("since") Instant since, @Param("minTiles") long minTiles);

    interface HeavyViewer {
        Long getUserId();

        long getTileCount();

        Instant getLastTileAt();
    }
}
