-- =============================================================================
-- V5 — Viewer session end reason (Phase 05A, see docs/phases/phase-05a-secure-viewer-backend.md)
-- =============================================================================

-- D10 — Every session ends for a reason we can audit: the buyer closed the tab
-- (CLOSED), another device took over (SUPERSEDED, D3), the heartbeat lease lapsed
-- (EXPIRED, D4), or an admin/creator action cut it short (REVOKED). The CHECK
-- constraint means a typo in application code fails fast at INSERT time instead
-- of silently corrupting the audit trail.
ALTER TABLE viewer_sessions ADD COLUMN end_reason VARCHAR(20);
ALTER TABLE viewer_sessions ADD CONSTRAINT chk_viewer_sessions_end_reason
    CHECK (end_reason IN ('CLOSED', 'SUPERSEDED', 'EXPIRED', 'REVOKED'));
