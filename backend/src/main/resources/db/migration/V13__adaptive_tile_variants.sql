-- =============================================================================
-- V13 — Adaptive tile resolution (Phase 16, see docs/phases/phase-16-adaptive-tile-resolution.md)
-- =============================================================================

-- D2 — one row per (version, page, variant). Every existing row was rendered at the desktop
-- resolution, so the DEFAULT labels them correctly without touching the data. VARCHAR (not a
-- Postgres enum or CHECK list) so a TABLET variant is a config change, never a migration (D1).
ALTER TABLE content_pages ADD COLUMN variant VARCHAR(16) NOT NULL DEFAULT 'DESKTOP';

-- The old key allowed exactly one row per page; two variants of a page would collide on it.
ALTER TABLE content_pages DROP CONSTRAINT uq_content_pages_version_page;
ALTER TABLE content_pages ADD CONSTRAINT uq_content_pages_version_page_variant
    UNIQUE (document_version_id, page_number, variant);

-- D7 — which resolution each tile request was served, for analytics. Existing rows were DESKTOP.
ALTER TABLE viewer_access_logs ADD COLUMN variant VARCHAR(16) NOT NULL DEFAULT 'DESKTOP';
