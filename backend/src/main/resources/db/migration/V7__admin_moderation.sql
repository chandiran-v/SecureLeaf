-- =============================================================================
-- V7 — Admin panel: post-moderation takedown + append-only admin audit log
-- (Phase 08, see docs/phases/phase-08-admin-panel.md)
-- =============================================================================

-- D5 — post-moderation. A product goes LIVE after processing; an admin can take it
-- down afterwards. taken_down_at/takedown_reason record WHEN and WHY, distinct from
-- unpublish_at because a creator's own unpublish leaves both columns NULL — that's
-- what republishProduct/restoreProduct check to tell "creator unpublished this" apart
-- from "an admin took this down" (only the latter blocks republishProduct).
ALTER TABLE products ADD COLUMN taken_down_at   TIMESTAMPTZ;
ALTER TABLE products ADD COLUMN takedown_reason TEXT;
ALTER TABLE products ADD CONSTRAINT chk_products_takedown_pair
    CHECK ((taken_down_at IS NULL) = (takedown_reason IS NULL));

-- D7 — append-only admin audit log. One row per admin mutation (suspend/reactivate a
-- user, take down/restore a product), written in the same transaction as the change
-- it records. admin_id is NOT a FK to users(id) ON DELETE CASCADE on purpose: users
-- are soft-deleted in this app (deleted_at), never hard-deleted, so the audit trail
-- can rely on the FK staying valid for the row's entire lifetime.
CREATE TABLE admin_actions (
    id          BIGSERIAL    PRIMARY KEY,
    admin_id    BIGINT       NOT NULL REFERENCES users (id),
    action      VARCHAR(50)  NOT NULL,
    target_type VARCHAR(50)  NOT NULL,
    target_id   BIGINT,
    reason      TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_admin_actions_created_at ON admin_actions (created_at DESC);
CREATE INDEX idx_admin_actions_admin_id   ON admin_actions (admin_id);

-- Same append-only pattern as payment_events (V4, D5): any UPDATE or DELETE raises.
-- TRUNCATE bypasses row triggers, which is what AbstractIntegrationTest relies on to
-- reset state between tests.
CREATE FUNCTION forbid_admin_action_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'admin_actions is append-only: % is not allowed', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_admin_actions_append_only
    BEFORE UPDATE OR DELETE ON admin_actions
    FOR EACH ROW EXECUTE FUNCTION forbid_admin_action_mutation();
