-- Purchase-time watermarking: each ACTIVE entitlement gets its own watermarked copy of every page
-- (every variant) of the version it is pinned to, rendered once in the background and stored. A
-- page view then serves the stored copy instead of decoding, watermarking and re-encoding the
-- page on every request. The view path still falls back to live watermarking for any page that
-- is not generated yet, so this table can be empty and everything still works.
--
-- watermark_version is part of the identity: bumping drm.prewatermark.version makes every row
-- stale; the reconciler deletes stale rows (and their objects) and regenerates them.
CREATE TABLE buyer_pages (
    id                  BIGSERIAL    PRIMARY KEY,
    entitlement_id      BIGINT       NOT NULL REFERENCES entitlements (id) ON DELETE CASCADE,
    document_version_id BIGINT       NOT NULL REFERENCES document_versions (id) ON DELETE CASCADE,
    page_number         INTEGER      NOT NULL CHECK (page_number >= 1),
    variant             VARCHAR(16)  NOT NULL,
    watermark_version   VARCHAR(16)  NOT NULL,
    bucket_name         VARCHAR(100) NOT NULL,
    object_key          VARCHAR(500) NOT NULL,
    file_size_bytes     BIGINT       CHECK (file_size_bytes > 0),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_buyer_pages_identity
        UNIQUE (entitlement_id, document_version_id, page_number, variant, watermark_version)
);

-- The unique index above starts with entitlement_id, so it also serves the view-path lookup, the
-- per-entitlement count and the ON DELETE CASCADE from entitlements. Postgres does NOT index
-- foreign-key columns by itself: without this one, deleting a document version would scan the
-- whole table to find the rows to cascade.
CREATE INDEX idx_buyer_pages_document_version_id ON buyer_pages (document_version_id);
