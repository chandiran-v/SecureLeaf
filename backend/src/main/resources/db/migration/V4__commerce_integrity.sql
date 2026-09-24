-- =============================================================================
-- V4 — Commerce integrity (Phase 4, see docs/phase4_implementation_prompt.md)
-- =============================================================================

-- D6 — At most ONE active entitlement per buyer + product.
-- V1 made (order_id, product_id) unique, which still lets two *different* orders
-- each grant the same buyer the same product. The old (buyer_id, product_id)
-- index was a plain index; recreate it as a UNIQUE *partial* index so a revoked
-- entitlement does not block a later re-purchase.
DROP INDEX IF EXISTS idx_entitlements_buyer_product_active;
CREATE UNIQUE INDEX uq_entitlements_buyer_product_active
    ON entitlements (buyer_id, product_id)
    WHERE status = 'ACTIVE';

-- D2 — Client-supplied idempotency key for initiateOrder (Stripe "Idempotency-Key" pattern).
-- D1 — The payment gateway's own order id (Razorpay "order_XXXX").
-- Both nullable (free orders have no gateway order); UNIQUE ignores NULLs in Postgres.
ALTER TABLE orders ADD COLUMN idempotency_key  VARCHAR(255);
ALTER TABLE orders ADD COLUMN gateway_order_id VARCHAR(255);
ALTER TABLE orders ADD CONSTRAINT uq_orders_idempotency_key  UNIQUE (idempotency_key);
ALTER TABLE orders ADD CONSTRAINT uq_orders_gateway_order_id UNIQUE (gateway_order_id);

-- D7 — Snapshot the commission split at purchase time so a future change to the
-- platform fee never rewrites historical creator earnings (PAY-10).
ALTER TABLE order_items ADD COLUMN platform_fee_paise     BIGINT NOT NULL DEFAULT 0 CHECK (platform_fee_paise >= 0);
ALTER TABLE order_items ADD COLUMN creator_earnings_paise BIGINT NOT NULL DEFAULT 0 CHECK (creator_earnings_paise >= 0);
-- Backfill any pre-existing rows (no fee was ever charged on them) before the CHECK is added.
UPDATE order_items SET creator_earnings_paise = price_paise;
ALTER TABLE order_items ADD CONSTRAINT chk_order_items_split_sums_to_price
    CHECK (platform_fee_paise + creator_earnings_paise = price_paise);

-- Latest decline reason, shown on the checkout page after a failed attempt.
ALTER TABLE payments ADD COLUMN failure_reason VARCHAR(255);

-- D5 — payment_events is an append-only audit log. Enforce it in the database, not
-- just by convention: any UPDATE or DELETE raises. (TRUNCATE does not fire row
-- triggers, which is what the integration tests use to reset state.)
CREATE FUNCTION forbid_payment_event_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'payment_events is append-only: % is not allowed', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_payment_events_append_only
    BEFORE UPDATE OR DELETE ON payment_events
    FOR EACH ROW EXECUTE FUNCTION forbid_payment_event_mutation();
