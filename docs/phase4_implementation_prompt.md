# Phase 4 — Commerce (orders → payments → entitlements)

> Implementation prompt / plan. Decisions are numbered (D1…D13) and referenced from code comments.

## Context

After Phase 3 a visitor can *find* and *sample* a product, but the Buy button is disabled
("Coming in Phase 4"). The commerce entities (`Order`, `OrderItem`, `Payment`, `PaymentEvent`,
`Entitlement`) and their tables exist since V1, and `schema.graphqls` declares `initiateOrder` /
`confirmMockPayment` / `myLibrary` — but nothing implements them.

Phase 4 makes buying work end to end: **Buy → order → (mock) Razorpay checkout → payment verified
→ entitlement granted → library, notifications, creator earnings.** The Phase 5 DRM viewer will
check the entitlement this phase creates before it serves a single tile.

Headline teaching topics (from `docs/learning-notes/README.md`): **idempotency, state machines,
audit logs, transactional integrity** — plus webhooks/HMAC, pessimistic locking and race conditions,
because those are what make the first four real.

### Decisions taken with the owner
- **Mock checkout page** where the buyer picks the outcome (success / decline / timeout).
- **Razorpay-shaped payment integration.** Real Razorpay comes later, so everything is modelled on
  Razorpay's actual contract (order → checkout → `razorpay_signature` → `X-Razorpay-Signature`
  webhook). Swapping in the real gateway = one new `PaymentGateway` class + keys + loading
  `checkout.js`; the order/payment/entitlement code does not change.
- **All extras included:** My Library page (PAY-09), creator earnings card (PAY-10), purchase
  notifications (PAY-08: in-app row + Redis Pub/Sub + async email).

---

## Verified starting facts

| Fact | Evidence |
|---|---|
| Commerce entities exist, no services/resolvers | `commerce/entity/*` only |
| A buyer can hold **two ACTIVE entitlements** for one product | `V1__init_schema.sql:207` unique is `(order_id, product_id)`; `:213` `(buyer_id, product_id)` index is **not** unique |
| No place to record the platform fee at purchase time | `order_items` has only `price_paise` |
| `payments.order_id` is UNIQUE → one payment row per order | `V1__init_schema.sql:179` |
| `payment_events.provider_event_id` is UNIQUE (nullable) | `V1__init_schema.sql:193` — perfect webhook dedup key |
| `products.version` exists → `Product` has `@Version` | `Product.java:66` — matters for the `totalSales` increment (D8) |
| Redis + mail starters on the classpath, never used | `pom.xml`; no `RedisTemplate`/`JavaMailSender` usage |
| README claims Mailpit runs in Docker Compose — it doesn't | `README.md` "Getting Started" vs `infra/docker-compose.yml` (**doc conflict**, fixed in this phase) |
| Frontend `Entitlement`/`Notification` types don't match the schema | `types/index.ts` (`isActive`, `message`) vs `schema.graphqls` (`status`, `body`) |

---

## Design decisions

### D1 — Payment gateway as a Strategy, shaped exactly like Razorpay
`PaymentGateway` interface (`createOrder`, `keyId`, `name`). `MockRazorpayGateway` implements it now;
`RazorpayGateway` (Razorpay Java SDK) later. Signature maths lives in `RazorpaySignatures` and is the
real Razorpay algorithm, so it is shared by mock and real:

- checkout signature = `HMAC_SHA256(order_id + "|" + payment_id, key_secret)` (hex)
- webhook signature  = `HMAC_SHA256(raw_request_body, webhook_secret)` (hex), header `X-Razorpay-Signature`

`MockRazorpayCheckoutController` (`POST /api/mock-gateway/orders/{gatewayOrderId}/pay`) stands in for
*Razorpay's servers + checkout.js*: it "charges", returns `{razorpay_payment_id, razorpay_order_id,
razorpay_signature}` like checkout.js's `handler`, and fires signed webhooks at our webhook endpoint.

### D2 — `initiateOrder` is idempotent in three layers
1. **Client idempotency key** (Stripe `Idempotency-Key` pattern): the detail page generates one UUID per
   visit and sends it; stored in the new `orders.idempotency_key UNIQUE`. Same key → same order back.
   Same key + different product → `IDEMPOTENCY_KEY_REUSED`.
2. **Reuse an open order:** an existing `PENDING` order for this buyer + product (and same price) is
   returned instead of creating another (covers "second tab").
3. **Per-buyer pessimistic lock** (`SELECT … FROM users WHERE id=? FOR UPDATE`) at the start, so two
   concurrent requests from one buyer run one after another and layer 2 sees the first one's order.

### D3 — One completion path, serialized by an order-row lock
Both the browser (`verifyPayment`) and the webhook (`payment.captured`) call
`PaymentCompletionService.recordCapture(...)`. It starts with `SELECT … FROM orders WHERE
gateway_order_id=? FOR UPDATE`; if the order is already `COMPLETED` it returns — a no-op. That is what
makes "webhook and browser arrive at the same time" and "webhook delivered twice" safe.

### D4 — Webhook is the source of truth; verified by HMAC over the raw body
`POST /api/webhooks/razorpay` is `permitAll` at the HTTP layer — authenticity comes from the signature,
not a JWT. The controller reads the body as a raw `String` (re-serialised JSON would change bytes and
break the HMAC), compares with `MessageDigest.isEqual` (constant time), dedups on
`payment_events.provider_event_id` (the `x-razorpay-event-id` header), checks the **amount matches the
order**, and returns 2xx for duplicates so the provider stops retrying.

### D5 — Explicit state machines; every payment transition is an append-only event
`OrderStatus.canTransitionTo`, `PaymentStatus.canTransitionTo`. Illegal transitions throw
`INVALID_STATE_TRANSITION`. Every payment status change appends a `payment_events` row
(`from_status → to_status`, source, provider event id, raw payload). Append-only is enforced **by the
database**: a trigger rejects `UPDATE`/`DELETE` on `payment_events` (V4).

Payment: `PENDING→COMPLETED`, `PENDING→FAILED`, `FAILED→FAILED` (another declined attempt),
`FAILED→COMPLETED` (retry succeeded on the same Razorpay order), `COMPLETED→REFUNDED`.
Order: `PENDING→COMPLETED`, `PENDING→FAILED` (superseded), `COMPLETED→REFUNDED`.
A declined card does **not** fail the order — Razorpay lets the buyer retry on the same order.

### D6 — Database backstop: at most one ACTIVE entitlement per buyer + product
V4 replaces the plain index with `CREATE UNIQUE INDEX … (buyer_id, product_id) WHERE status='ACTIVE'`.
The locks in D2/D3 should make a duplicate impossible; the index makes it impossible *even if the
application code is wrong*.

### D7 — Money: integer paise, fee in basis points, snapshot at purchase time
`commerce.platform-fee-bps: 1000` (10%). `fee = round_half_up(price × bps / 10000)`,
`creatorEarnings = price − fee` (derive one side by subtraction so the two always sum exactly).
Both are stored on `order_items` at order time (V4) with a CHECK that they sum to `price_paise`, so a
future commission change never rewrites past earnings.

### D8 — `totalSales` via an atomic `UPDATE … SET total_sales = total_sales + 1`
Read-modify-write on the entity would lose updates under concurrency (and trip `Product.@Version`
optimistic locking, failing a paid purchase). A single SQL increment is atomic in Postgres.

### D9 — Notifications: rows inside the purchase transaction, side effects after commit
Notification rows (buyer `PURCHASE_SUCCESS`, creator `SALE_RECEIVED`) are inserted in the same
transaction as the entitlement — atomic. Redis Pub/Sub publish and the email run in a
`@TransactionalEventListener(phase = AFTER_COMMIT)` on an async executor: never email about a purchase
that later rolls back. Nobody subscribes to the Redis channel yet — Phase 6 bridges it to the browser;
the bell polls `myNotifications` until then.

### D10 — Free products: a ₹0 order, completed immediately, no payment row
Keeps "every entitlement points at an order" (`entitlements.order_id NOT NULL`) true, gives an audit
trail, and there is no money movement to record.

### D11 — `Product.ownedByMe` via `@BatchMapping`
One `SELECT product_id FROM entitlements WHERE buyer_id=? AND product_id IN (…)` per request, not per
product — the N+1 trap from Phase 3 in a new shape. Anonymous → all `false`.

### D12 — Security
- BOLA: another buyer's order id → `NOT_FOUND` (not 403 — don't confirm it exists).
- Creator cannot buy own product; product must be `LIVE` and not deleted.
- Library keeps showing products that were later unpublished/deleted — a sale is a promise.
- The mock gateway exists only when `payment.gateway.provider=mock`. `application-prod.yml` defaults to
  `razorpay`, and since no `razorpay` bean exists yet **prod refuses to start** — fail-fast beats a
  public "mark as paid" endpoint in production.

### D13 — The timeout scenario (why webhooks exist)
Mock "timeout": the gateway captures the money and schedules the webhook, but answers the browser with
`504`. The checkout page says "confirming your payment…" and polls `order(id)`; the webhook completes
the order. Demonstrates: the browser is not a reliable messenger; the webhook is.

---

## GraphQL changes

```graphql
type Order { id, status, totalAmountPaise, product: Product!, gatewayOrderId, failureReason, createdAt }
type InitiateOrderPayload { order: Order!, gatewayOrderId, gatewayKeyId, currency: String! }   # clientSecret removed
type CreatorEarnings { salesCount, grossSalesPaise, platformFeePaise, netEarningsPaise }
input VerifyPaymentInput { orderId, gatewayOrderId, gatewayPaymentId, gatewaySignature }
Product.ownedByMe: Boolean!
Query:    order(id), myLibrary, myNotifications, creatorEarnings
Mutation: initiateOrder(productId, idempotencyKey), verifyPayment(input), markNotificationRead(id)
```
`confirmMockPayment` is **removed** — it was never implemented, and a mutation that marks an order paid
without any proof is exactly the anti-pattern this phase teaches against.

## REST
- `POST /api/webhooks/razorpay` — public, HMAC-verified.
- `POST /api/mock-gateway/orders/{gatewayOrderId}/pay` `{outcome: SUCCESS|DECLINE|TIMEOUT}` — mock only.

## Migration V4
Unique partial index on active entitlements; `orders.idempotency_key`, `orders.gateway_order_id`
(both UNIQUE); `order_items.platform_fee_paise` / `creator_earnings_paise` + sum CHECK;
`payments.failure_reason`; append-only trigger on `payment_events`.

## Tests
`CommerceIT` (happy path, replay, BOLA, bad signatures, duplicate webhooks, concurrent completion race,
decline→retry, amount mismatch, free product, append-only trigger, library after unpublish, ownedByMe,
earnings) and `NotificationIT`. Frontend: detail-page buy states, checkout page, library page.

## Out of scope (deferred)
Real Razorpay (keys, `checkout.js`, ngrok for webhooks), refunds UI, payouts workflow, real-time push
(SSE bridge from Redis → browser, Phase 6), reviews (Phase 6), the DRM viewer itself (Phase 5),
rate limiting (MVP-2), a reconciliation job that sweeps stale `PENDING` orders (listed as the main
weakness in the note).
