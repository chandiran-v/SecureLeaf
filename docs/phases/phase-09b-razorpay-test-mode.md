# Phase 09B — Real Razorpay integration (test mode)

> Spec for an unattended run. Decisions are numbered (D1…) and referenced from code comments.
> Requirements: **PAY-02** (real provider behind the existing gateway abstraction), plus the Phase 4 note's "weakest point" (no reconciliation job).
> Depends on: Phase 09. Part of the **Launch track** (09B → 09C → 09D → 09E go-live).
> **Test mode only.** Live keys and KYC come with MVP2 (Phase 18). This phase must make it impossible to use live keys by accident.

## Context

Phase 4 built commerce against a **Razorpay-shaped mock**:
- `PaymentGateway` has `name()`, `keyId()` and `createOrder(amountPaise, currency, receipt)`.
- `MockRazorpayGateway` and `MockRazorpayCheckoutController` stand in for Razorpay.
- `RazorpaySignatures` verifies `razorpay_signature` and the `X-Razorpay-Signature` webhook signature.
- `RazorpayWebhookController` handles `payment.captured` and `payment.failed`.
- `PaymentCompletionService` is idempotent: it takes an order-row lock, deduplicates on `payment_events.provider_event_id`, and grants entitlements.
- `CommerceConfig` **refuses to start in prod with the mock**, and says `razorpay` isn't implemented yet.

This phase adds the real provider **without changing** the order → payment → entitlement flow. That was the point of Phase 4's design.

We deploy publicly in **test mode**: anyone can check out with Razorpay's test cards or test UPI, and **no real money moves**. That has to be unmistakable in the UI.

## Decisions

- **D1 — `RazorpayGateway implements PaymentGateway`.**
  - Selected with `payment.gateway.provider=razorpay`.
  - Calls Razorpay's REST API directly with Spring's `RestClient`, using HTTP Basic auth (`key_id:key_secret`), base URL `https://api.razorpay.com/v1` (configurable, for tests).
  - **No Razorpay SDK:** fewer dependencies, and fully testable with `MockRestServiceServer`.
  - `createOrder`: `POST /orders` with `{ amount (paise), currency: "INR", receipt: "sl_order_<id>", notes: { orderId } }`, returning `id`.
  - Timeouts: connect 3 s, read 10 s. A gateway error becomes a clear `BusinessException` (`PAYMENT_GATEWAY_UNAVAILABLE`) and never returns a half-created order.
- **D2 — Extend the gateway interface for the rest of the lifecycle.** Implement all of these in the mock too:
  - `fetchPayment(paymentId)`: status, amount, `fee`, `tax`, `method`
  - `fetchOrderPayments(gatewayOrderId)`
  - `capture(paymentId, amountPaise)`
  - `refund(paymentId, amountPaise, notes)`
- **D3 — Checkout in the browser.** `InitiateOrderPayload` gains `provider: String!` (`MOCK` | `RAZORPAY`). When it's `RAZORPAY`, the frontend:
  1. Loads `https://checkout.razorpay.com/v1/checkout.js` once, in a `useRazorpayCheckout` hook.
  2. Opens `new Razorpay({ key, order_id, amount, currency, name: "SecureLeaf", description: product title, prefill: { email, name }, theme: { color: emerald } })`.
  3. `handler(response)` → the existing `verifyPayment` mutation.
  4. `modal.ondismiss` → back to the checkout page showing "Payment cancelled".
  5. `payment.failed` event → show Razorpay's error description.

  The mock flow stays exactly as it is for `MOCK` (dev and tests). Update the CSP from Phase 09 to allow `checkout.razorpay.com` (script and frame) and `api.razorpay.com` (connect).
- **D4 — Capture.** Treat `payment.captured` as success (as now). Also handle **`payment.authorized`**: call `capture` (idempotent on Razorpay's side), and let the resulting `payment.captured` webhook complete the order. This works whether or not auto-capture is enabled on the account. Document the recommended dashboard setting (auto-capture on) in the runbook.
- **D5 — Gateway fees: the platform pays them.** Migration (next free `V`): add `gateway_fee_paise` and `gateway_tax_paise` (`BIGINT NULL`) to `payments`, filled from `fetchPayment` on capture. **Creator earnings don't change**: price − the 10% snapshot from Phase 4, D7.
  - Admin `platformStats` gains `gatewayFeesPaise` and `platformNetPaise` = platform fee − gateway fee − tax.
  - Record the reasoning in the note: it's simple, and creators see a clean 90%.
- **D6 — Refunds (admin only).**
  - Mutation `refundOrder(orderId: ID!, reason: String!): Order!` → `gateway.refund(full amount)`.
  - On success: order and payment `REFUNDED`, entitlement `REVOKED` with `revocation_reason`, a `payment_events` row appended, an `admin_actions` row (Phase 8, D7), and a buyer notification and email.
  - Handle webhook `refund.processed` idempotently. A duplicate or late webhook changes nothing.
  - **Creator earnings exclude refunded orders** everywhere: dashboard stats, `creatorEarnings`, and the 09C balance.
  - Partial refunds are out of scope.
- **D7 — Reconciliation job**, which fixes the Phase 4 weak point:
  - Every 10 minutes, for `PENDING` orders with a `gateway_order_id` older than 15 minutes, call `fetchOrderPayments`.
  - A captured payment is completed through **the same idempotent path** as the webhook.
  - Orders older than 24 hours with nothing captured become `FAILED` (reason "expired").
  - Controlled by `payment.reconciliation.enabled` (default true; **false in tests**, where tests call the job method directly).
- **D8 — Payment mode is visible, and live keys are blocked.**
  - New public query `platformInfo { paymentMode: MOCK | TEST | LIVE, supportEmail }`, where mode = provider + key prefix (`rzp_test_` → TEST, `rzp_live_` → LIVE).
  - **Startup guard:** if the key id starts with `rzp_live_` while `payment.live-enabled` is not `true`, **refuse to start**. `live-enabled` defaults to false; Phase 18 flips it.
  - Allow `provider=razorpay` in prod (replacing the current "not implemented" refusal). The mock stays forbidden in prod.
- **D9 — Demo banner.** When `paymentMode` isn't `LIVE`:
  - A thin site-wide banner: "Demo mode — payments use Razorpay **test mode**. No real money is charged."
  - On the checkout page, a panel with test hints: a link to Razorpay's official test cards and UPI page, plus the success UPI id `success@razorpay`.
  - The banner can be dismissed for the rest of the browser session, but it's always shown on checkout.
- **D10 — Configuration.**
  - Env vars: `PAYMENT_GATEWAY`, `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, `RAZORPAY_WEBHOOK_SECRET`, `PAYMENT_LIVE_ENABLED`.
  - Secrets are never logged.
  - Phase 09's prod validator must reject blank Razorpay secrets when `provider=razorpay`.

## Acceptance criteria
1. `RazorpayGateway.createOrder` sends the correct method, path, Basic auth header and body (amount in paise, receipt), and parses `id`. A 4xx or 5xx becomes `PAYMENT_GATEWAY_UNAVAILABLE` (`MockRestServiceServer`).
2. `fetchPayment`, `capture`, `refund` and `fetchOrderPayments` are each covered for success and failure.
3. Signature checks: a known-good vector passes for both `razorpay_signature` and the webhook header, and tampered ones fail (reuse or extend Phase 4's tests).
4. End to end with a stubbed gateway:
   - initiate → `verifyPayment` → entitlement granted
   - duplicate `payment.captured` → no second entitlement
   - `payment.authorized` → capture called once
5. Refund: the admin refunds → statuses change, the entitlement is revoked (the reader returns 403), the audit row and the notification exist, creator earnings drop, and a replayed `refund.processed` changes nothing. A non-admin gets `ACCESS_DENIED`.
6. Reconciliation: a pending order whose gateway shows a capture gets completed. A 25-hour-old order with no capture becomes FAILED. Already-completed orders aren't touched.
7. Startup guard: `rzp_live_` with `live-enabled=false` fails to start with a clear message. `rzp_test_` starts, and `platformInfo.paymentMode` = `TEST`.
8. Fees stored on capture, and `platformStats` shows fees and platform net correctly.
9. Frontend:
   - with `provider=RAZORPAY`, the checkout script loads once and the Razorpay constructor gets the right options (mocked `window.Razorpay`)
   - handler → `verifyPayment`; dismiss → "cancelled" state
   - the banner shows for `TEST` and not for `LIVE`
   - the mock flow still works for `MOCK`
10. All existing tests stay green.

## Out of scope
- Live mode and KYC (Phase 18).
- International cards, EMI, subscriptions.
- Partial refunds.
- Razorpay Route (automatic split payments).
- Invoices with GST numbers.

## Learning note
Update `docs/learning-notes/phase-04-commerce.md` with a "Real gateway" addendum, or create `phase-09b-razorpay.md` if the addendum gets too large; either way every template section must be covered. Headline topics:
- the adapter / ports-and-adapters pattern paying off (the mock → real swap touched no business logic)
- test vs live mode, and the guard against accidental live keys
- two paths to the same idempotent completion (browser handler, webhook, and now reconciliation)
- authorize vs capture
- refunds as a state transition
- who absorbs gateway fees
- testing HTTP clients with `MockRestServiceServer`
- CSP for third-party checkout
