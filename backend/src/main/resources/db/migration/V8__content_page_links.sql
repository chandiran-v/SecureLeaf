-- =============================================================================
-- V8 — Clickable links in the secure reader
--
-- Pages reach the browser only as watermarked images, so the PDF's own link
-- annotations are lost. The processing pipeline now extracts them and stores
-- each link's rectangle as FRACTIONS of the rendered page (0..1, origin top-left,
-- page rotation already applied), so the reader can lay invisible click targets
-- over the image at any size or zoom.
-- =============================================================================

CREATE TABLE content_page_links (
    id              BIGSERIAL        PRIMARY KEY,
    content_page_id BIGINT           NOT NULL REFERENCES content_pages (id) ON DELETE CASCADE,
    left_ratio      DOUBLE PRECISION NOT NULL CHECK (left_ratio   >= 0 AND left_ratio   <= 1),
    top_ratio       DOUBLE PRECISION NOT NULL CHECK (top_ratio    >= 0 AND top_ratio    <= 1),
    width_ratio     DOUBLE PRECISION NOT NULL CHECK (width_ratio  >  0 AND width_ratio  <= 1),
    height_ratio    DOUBLE PRECISION NOT NULL CHECK (height_ratio >  0 AND height_ratio <= 1),
    link_type       VARCHAR(10)      NOT NULL CHECK (link_type IN ('URL', 'PAGE')),
    target_url      VARCHAR(2048),
    target_page     INTEGER          CHECK (target_page >= 1),
    -- A URL link has a URL and no page; a PAGE link has a page and no URL.
    CONSTRAINT chk_content_page_links_target CHECK (
        (link_type = 'URL'  AND target_url IS NOT NULL AND target_page IS NULL) OR
        (link_type = 'PAGE' AND target_page IS NOT NULL AND target_url IS NULL)
    )
);
CREATE INDEX idx_content_page_links_content_page_id ON content_page_links (content_page_id);

-- NULL = links not extracted yet (documents processed before V8). The backfill job
-- (PageLinkBackfillJob) finds these, extracts their links once, and stamps the time.
ALTER TABLE document_versions ADD COLUMN links_extracted_at TIMESTAMPTZ;
