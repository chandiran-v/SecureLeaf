-- Phase 07 — password reset (AUTH-07).
--
-- users.reset_token_hash / reset_token_expires_at and the reviews table already exist from
-- V1 (unused columns, sized for exactly this feature). The only thing missing is an index:
-- resetPassword looks a user up BY this hash on every attempt, and without an index that's a
-- full table scan of users. A partial index (only rows that actually have a pending reset)
-- keeps it small — the same pattern V1 already uses for idx_users_deleted_at.
CREATE INDEX idx_users_reset_token_hash ON users (reset_token_hash) WHERE reset_token_hash IS NOT NULL;
