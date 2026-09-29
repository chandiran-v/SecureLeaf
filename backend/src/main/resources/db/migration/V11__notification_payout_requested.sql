-- =============================================================================
-- V11 — PAYOUT_REQUESTED notification type (Phase 09C D3)
-- =============================================================================
-- ALTER TYPE ... ADD VALUE gets its own migration on purpose. Flyway runs each migration in a
-- transaction, and Postgres refuses to USE a freshly added enum value inside the transaction that
-- added it ("unsafe use of new value"). Nothing in this file uses it; code that does runs later.
ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'PAYOUT_REQUESTED';
