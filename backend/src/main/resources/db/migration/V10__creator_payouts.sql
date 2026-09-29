-- =============================================================================
-- V10 — Creator payouts (Phase 09C, see docs/phases/phase-09c-payouts-receipts-legal.md)
-- =============================================================================

-- D1 — the hold period is measured from the moment the sale COMPLETED, not from the order's
-- created_at (a PENDING order can sit for hours) and not from updated_at (which any later write
-- moves). Set by Order.transitionTo(COMPLETED); kept when the order is later REFUNDED.
ALTER TABLE orders ADD COLUMN completed_at TIMESTAMPTZ;
UPDATE orders SET completed_at = updated_at WHERE status IN ('COMPLETED', 'REFUNDED');
CREATE INDEX idx_orders_completed_at ON orders (completed_at) WHERE completed_at IS NOT NULL;

-- D2 — where the money is to be sent, SNAPSHOTTED at request time so a profile edit after the
-- admin has already looked at the request cannot redirect the transfer.
ALTER TABLE creator_payouts ADD COLUMN payout_destination VARCHAR(255);

-- D2 — defence in depth for "only one open request at a time". The service already checks under a
-- row lock; this makes a second open request impossible even if a future code path forgets to.
CREATE UNIQUE INDEX uq_creator_payouts_one_open
    ON creator_payouts (creator_id)
    WHERE status IN ('REQUESTED', 'APPROVED', 'PROCESSING');
