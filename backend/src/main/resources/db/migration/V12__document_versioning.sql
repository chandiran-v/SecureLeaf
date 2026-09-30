-- =============================================================================
-- V12 — Full document versioning (Phase 15, see docs/phases/phase-15-document-versioning.md)
-- =============================================================================

-- D1 — the current version is an explicit pointer, not "whichever row sorts last".
-- ON DELETE SET NULL: the pointer must never block deleting a version row (document_versions
-- already cascades from products, and products<->document_versions is a cycle).
ALTER TABLE products ADD COLUMN current_document_version_id BIGINT
    REFERENCES document_versions (id) ON DELETE SET NULL;

-- Backfill: each product points at its latest PROCESSED version (what MVP1 inferred by ordering).
-- A product whose upload never finished keeps NULL until its pipeline completes.
UPDATE products p
SET current_document_version_id = latest.id
FROM (
    SELECT DISTINCT ON (product_id) id, product_id
    FROM document_versions
    WHERE processed_at IS NOT NULL
    ORDER BY product_id, version_number DESC
) latest
WHERE latest.product_id = p.id;

-- D3 — what happens to buyers already holding an older version. Existing rows were MVP1 uploads,
-- which never moved anyone, so NEW_BUYERS_ONLY is the honest default.
ALTER TABLE document_versions ADD COLUMN update_policy VARCHAR(40) NOT NULL DEFAULT 'NEW_BUYERS_ONLY'
    CHECK (update_policy IN ('NEW_BUYERS_ONLY', 'FREE_UPDATE_FOR_EXISTING'));

-- D3 — batch-migration checkpoint. NULL on a processed FREE_UPDATE_FOR_EXISTING version means
-- "entitlements still to move"; the poller resumes exactly those, so a crash between chunks
-- loses nothing. Pre-existing versions are marked done: there is nothing to migrate for them.
ALTER TABLE document_versions ADD COLUMN entitlements_migrated_at TIMESTAMPTZ;
UPDATE document_versions SET entitlements_migrated_at = COALESCE(processed_at, created_at);

-- D5 — soft retirement; the row, its raw PDF and its history stay.
ALTER TABLE document_versions ADD COLUMN retired_at TIMESTAMPTZ;

CREATE INDEX idx_document_versions_migration_pending
    ON document_versions (id)
    WHERE update_policy = 'FREE_UPDATE_FOR_EXISTING' AND entitlements_migrated_at IS NULL;
