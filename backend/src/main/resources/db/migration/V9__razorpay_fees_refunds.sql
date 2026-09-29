-- =============================================================================
-- V9 — Real Razorpay (Phase 09B, see docs/phases/phase-09b-razorpay-test-mode.md)
-- =============================================================================

-- D5 — what the gateway charged US for processing this payment. Filled from the gateway's
-- "fetch payment" call when the capture is applied. NULL = not known (older rows, or the
-- gateway lookup failed at that moment). The platform absorbs these; creator earnings on
-- order_items are untouched.
ALTER TABLE payments ADD COLUMN gateway_fee_paise BIGINT CHECK (gateway_fee_paise >= 0);
ALTER TABLE payments ADD COLUMN gateway_tax_paise BIGINT CHECK (gateway_tax_paise >= 0);

-- D6 — refund bookkeeping. The status change itself is the payment_status REFUNDED value
-- that already existed since V1; these columns say WHICH gateway refund and WHY.
ALTER TABLE payments ADD COLUMN refund_id     VARCHAR(255);
ALTER TABLE payments ADD COLUMN refund_reason TEXT;
ALTER TABLE payments ADD COLUMN refunded_at   TIMESTAMPTZ;

-- D7 — the reconciliation job scans PENDING orders that have a gateway order id.
CREATE INDEX idx_orders_pending_gateway ON orders (created_at)
    WHERE status = 'PENDING' AND gateway_order_id IS NOT NULL;

-- D6 — buyer notification when their purchase is refunded. (An enum value added here must not
-- be USED in this same migration; nothing below does.)
ALTER TYPE notification_type ADD VALUE IF NOT EXISTS 'REFUND_PROCESSED';
