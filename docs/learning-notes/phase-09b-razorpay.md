# Phase 09B — Real Razorpay integration (test mode)

> **Status:** Done
> **Built:** 2026-09-29
> **Requirement IDs covered:** PAY-02 (a real provider behind the gateway abstraction — `docs/requirements.md` still words it as "mock provider for MVP"; see §10), and it closes the "no reconciliation job" weak point named at the end of the [Phase 4 note](phase-04-commerce.md). Extends PAY-04, PAY-06 and PAY-07 (payment record, audit log, idempotent webhooks) with refunds and fees.
> **Spec:** [`docs/phases/phase-09b-razorpay-test-mode.md`](../phases/phase-09b-razorpay-test-mode.md) — decisions D1–D10 are referenced throughout.
> **Commits:** see `git log --grep "Phase 09B"` on the branch `auto/issue-28`.

---

## 1. What we built, in plain English

Until now, "paying" on SecureLeaf was a simulation: a fake gateway inside our own server said "success" and we granted the purchase. This phase plugs in the **real Razorpay** — India's card/UPI payment provider — but only in **test mode**. Razorpay's test mode is a full copy of the real system where you pay with special test card numbers and no money moves. That lets us deploy publicly and let anyone try the whole buying journey safely.

A buyer clicks **Pay**, Razorpay's own popup opens (we never see card details), they pay with a test card or the test UPI id `success@razorpay`, and the same order → payment → entitlement flow from Phase 4 finishes the job. Around that we added everything a real payment system needs and a mock never did:

- **Refunds** (admin only): full refund through Razorpay; the buyer's access is revoked, they're notified and emailed, and there's an audit row.
- **Reconciliation**: a background job that asks Razorpay "what actually happened?" about orders that look stuck, so a paid order can never be lost just because two messages went missing.
- **Fee accounting**: Razorpay charges *us* ~2% + GST per payment. We record it and show the platform's true net. Creators still see a clean 90%.
- **A "Demo mode" banner** so nobody mistakes the test deployment for a shop that charges real money — plus a safety catch that **refuses to start** if someone accidentally configures a *live* key before we're ready.

**Before this phase:** only a Razorpay-shaped mock; no refunds; a paid order could stay `PENDING` forever if both the browser and the webhook failed; nothing told a visitor that payments were fake.
**After this phase:** `PAYMENT_GATEWAY=razorpay` runs against real Razorpay test mode; `mock` still works for dev and tests; the business logic (orders, entitlements) did not change at all.

---

## 2. Why it matters

- **A demo that only fakes payments proves little.** Real Razorpay means real webhooks, real signatures, real failures (declines, timeouts, authorizations that need capturing). Interviewers ask about exactly these.
- **The design was built for this swap.** Phase 4 hid the gateway behind an interface. This phase is the payoff: we added a second implementation and the order/entitlement code was untouched (it only *gained* features — fee capture, a third completion path).
- **Money code fails silently.** A refund that doesn't revoke access, a payment nobody noticed, a live key in a test environment — each is a quiet, expensive bug. Most of this phase is making those impossible or loudly visible.
- **Skip it and:** launching publicly would mean either fake payments (embarrassing) or real ones with no refunds and no way to recover a lost webhook (dangerous).

---

## 3. New concepts introduced

### 3.1 Ports and adapters (the gateway abstraction paying off)

**What it is:** The rest of the app talks to an *interface* (the "port", `PaymentGateway`); each real-world service is an *adapter* that implements it (`MockRazorpayGateway`, `RazorpayGateway`).

**The analogy:** A wall socket. Your lamp doesn't care whether the electricity comes from solar or coal; it needs a plug of the right shape. Swapping the power plant doesn't mean rewiring the lamp.

**Why we needed it here:** `OrderService`, `PaymentCompletionService`, `RefundService` and the reconciliation job call `paymentGateway.createOrder / fetchPayment / capture / refund`. Whether that's an in-memory map or an HTTPS call to Razorpay is invisible to them.

**In our code:** `backend/src/main/java/com/secureleaf/commerce/gateway/PaymentGateway.java:21`
```java
public interface PaymentGateway {
    String createOrder(long amountPaise, String currency, String receipt);
    GatewayPayment fetchPayment(String paymentId);
    List<GatewayPayment> fetchOrderPayments(String gatewayOrderId);
    GatewayPayment capture(String paymentId, long amountPaise);
    GatewayRefund refund(String paymentId, long amountPaise, Map<String, String> notes);
```
Selected by one property — `payment.gateway.provider` — in `RazorpayGatewayConfig.java` (real) or `@ConditionalOnProperty` on the mock.

**What breaks without it:** Razorpay's JSON and URLs leak into business code. Testing needs the internet or a hand-rolled fake in every test; switching providers (Stripe) means touching everything.

### 3.2 Calling an HTTP API without an SDK (and testing it with `MockRestServiceServer`)

**What it is:** Razorpay offers a Java SDK; we didn't use it. `RazorpayGateway` uses Spring's `RestClient` with HTTP Basic auth (`key_id:key_secret`), five small endpoints.

**The analogy:** Ordering by phone (you say exactly what you want) instead of installing the restaurant's app — fewer things to keep updated, and you can see every word you said.

**Why we needed it here:** Fewer dependencies on a memory-tight server, and the whole client is unit-testable: `MockRestServiceServer` replaces the network and lets a test *assert the exact request we send* (method, path, `Authorization` header, JSON body in paise) and feed back canned Razorpay responses.

**In our code:** `RazorpayGateway.java:62` (`createOrder`), `RazorpayGatewayTest.java` (every endpoint, success and failure).
```java
server.expect(requestTo(BASE + "/orders"))
      .andExpect(method(HttpMethod.POST))
      .andExpect(header("Authorization", BASIC))
      .andExpect(jsonPath("$.amount").value(49900))
      .andRespond(withSuccess("{\"id\":\"order_XYZ\"...}", MediaType.APPLICATION_JSON));
```
**Errors:** every 4xx/5xx, timeout or connection failure becomes one `BusinessException(PAYMENT_GATEWAY_UNAVAILABLE)` (`RazorpayGateway.java:179`). `createOrder` is the first thing `initiateOrder` does after inserting the order row, so the exception rolls the transaction back — no half-created order. **Timeouts** (connect 3 s, read 10 s, `RazorpayGatewayConfig.java:26`) stop a stalled gateway from hanging request threads forever.

**What breaks without it:** No timeout = one slow Razorpay response ties up a thread per checkout click until the pool is exhausted. Untested HTTP code = you find out the header was wrong in production.

### 3.3 Test mode vs live mode, and the guard against live keys

**What it is:** Razorpay issues two key pairs: `rzp_test_…` (fake money) and `rzp_live_…` (real money). The prefix is the only difference the code can see.

**The analogy:** A flight simulator and a real plane share the same cockpit. You do not want to discover which one you're in by landing.

**Why we needed it here:** We deploy publicly on test keys. Pasting the wrong key into the environment must not silently start charging real cards before KYC, refund policy and legal pages exist (Phase 18).

**How it works:** `CommerceConfig` runs at startup: `rzp_live_` key **and** `payment.live-enabled` not `true` → the app throws and refuses to boot, with a message that never echoes the key. `PlatformInfoService.paymentMode` derives `MOCK | TEST | LIVE` from provider + key prefix, so the UI banner can't disagree with the server.

**In our code:** `CommerceConfig.java:40`
```java
if (keyId != null && keyId.startsWith(LIVE_KEY_PREFIX) && !paymentProperties.liveEnabled()) {
    throw new IllegalStateException("Refusing to start: RAZORPAY_KEY_ID is a LIVE key (rzp_live_…) but payment.live-enabled is not true. ...");
```
Also: the mock provider is refused in `prod`; `ProdSecretsConfig` refuses blank Razorpay credentials when `provider=razorpay`.

**What breaks without it:** A one-character config mistake charges real customers with a half-tested system. Fail-fast turns a possible incident into a five-second failed deploy.

### 3.4 Authorize vs capture

**What it is:** Card payments have two steps: **authorize** (the bank *reserves* the money) and **capture** (we *claim* it). Razorpay can do both automatically ("auto-capture") or leave capture to us.

**The analogy:** A hotel putting a hold on your card at check-in (authorize), then charging it at check-out (capture). The hold is not a sale.

**Why we needed it here:** Whether an account auto-captures is a dashboard setting we don't control from code. So we handle both: `payment.captured` completes the order (as before); `payment.authorized` makes us call `capture`, which triggers a `payment.captured` webhook that completes the order through the normal path. An authorization nobody captures is refunded by Razorpay after a few days — the customer keeps nothing and we get nothing.

**In our code:** `PaymentCompletionService.java:132` (`recordAuthorization`) — deliberately **not** `@Transactional`, because it makes an HTTP call and holding a DB transaction across a network call is how one slow gateway starves the connection pool. It also checks status and amount *before* capturing. If the capture call fails, the webhook controller answers **503** so Razorpay redelivers (`RazorpayWebhookController`).

**What breaks without it:** On a non-auto-capture account every payment is "authorized", none is captured, no order ever completes, and every buyer's money is released back after a few days.

### 3.5 Three messengers, one idempotent completion

**What it is:** A successful payment is reported to us by up to three independent routes: the browser handler (`verifyPayment`), Razorpay's webhook, and — new — the reconciliation job. All three funnel into `applyCapture`, which takes a row lock on the order and returns early if it's already `COMPLETED`.

**The analogy:** Three couriers may deliver the same parcel receipt. The office (a) processes one at a time, and (b) throws away duplicates.

**Why we needed it here:** Phase 4's admitted weakest point: if the browser tab closes *and* the webhook is lost, a paid order stays `PENDING` forever. Reconciliation is the third courier that goes and fetches the truth.

**How it works:** every 10 minutes, for `PENDING` orders older than 15 minutes with a gateway order id, ask `fetchOrderPayments`. A captured payment → `recordReconciledCapture` (same path, source `RECONCILIATION`). An *authorized* one → capture it, then complete. Nothing captured and older than 24 h → `FAILED` ("expired"). It has no locking of its own; the lock inside the completion path makes it safe to run while a webhook lands, or on two instances.

**In our code:** `PaymentReconciliationJob.java:57` (`reconcile`) and `PaymentCompletionService.java:113`.

**What breaks without it:** Paid-but-unfulfilled orders that nobody notices until a customer emails. With the job, the worst case is a ~25-minute delay.

### 3.6 Refunds as a state transition

**What it is:** A refund doesn't delete anything. Order `COMPLETED → REFUNDED`, payment `COMPLETED → REFUNDED`, entitlement `ACTIVE → REVOKED`. History is preserved; what the buyer can *do* changes.

**The analogy:** A ledger: you don't erase a sale, you add a reversing entry.

**How it works:** `RefundService.refundOrder` (`RefundService.java:70`) locks the order, calls `gateway.refund`, then `applyRefund` does all the effects: audit event in `payment_events`, entitlement revoked with `revocation_reason`, sales counter decremented (floored at 0), buyer notification + email. `AdminOrderResolver` gates it with `hasRole('ADMIN')` and the service writes the `admin_actions` row in the same transaction. The viewer's tile endpoint re-checks the entitlement on every request, so revoking it closes the reader immediately.

**Two entrances, one effect:** the webhook `refund.processed` (`recordRefundProcessed`) covers refunds made from Razorpay's dashboard and the case where our Razorpay call succeeded but our commit failed. Both no-op if the order is already `REFUNDED`, so duplicate or late webhooks change nothing.

**Why the gateway call sits inside the transaction:** the row lock stops two admins refunding the same order twice; if Razorpay rejects the call, the exception rolls everything back.

**Creator earnings** exclude refunded orders automatically: all earnings queries filter `status = COMPLETED`.

**What breaks without it:** A refund that only flips a status leaves the buyer able to read the PDF they were refunded for.

### 3.7 Who absorbs the gateway fee

**What it is:** Razorpay keeps ~2% + 18% GST of that fee from every payment. Someone must eat it.

**Our choice:** the platform. `payments.gateway_fee_paise` / `gateway_tax_paise` (migration `V9`) are filled from `fetchPayment` on capture (`PaymentCompletionService.java:246`). Creator earnings stay *price − 10% snapshot*. `platformStats` gains `gatewayFeesPaise` and `platformNetPaise = platform fee − fee − tax`. Fees are counted for `COMPLETED` **and** `REFUNDED` orders — a refund does not give Razorpay's fee back, so a refunded sale is a genuine loss (`AdminAnalyticsRepository.java:66`).

**Fee lookup is best-effort:** a failed `fetchPayment` must never block a buyer who has paid from getting access, so fee columns simply stay `NULL` ("unknown") and we log it.

### 3.8 CSP for third-party checkout

**What it is:** Phase 9's Content-Security-Policy says "only load scripts from our own origin". Razorpay's checkout needs three deliberate holes: `script-src https://checkout.razorpay.com`, `frame-src https://checkout.razorpay.com https://api.razorpay.com` (the popup is an iframe), and `connect-src https://api.razorpay.com`. Updated in `SecurityConfig.java` and `frontend/vercel.json`.

**Why it matters:** The right way to allow a third party is to name exactly the hosts you need — not `'unsafe-inline'` or `*`. The card form lives in Razorpay's iframe, so our page and our JavaScript never touch card data.

---

## 4. Best practices applied

| Practice | What we did | Why it matters | Where |
|---|---|---|---|
| Ports & adapters | Real gateway is a second implementation of `PaymentGateway` | Business code unchanged by the swap | `PaymentGateway.java:21` |
| Fail fast on dangerous config | Refuse to start with a live key while `live-enabled=false`; mock forbidden in prod; blank secrets rejected | Config mistakes become failed deploys, not incidents | `CommerceConfig.java:40`, `ProdSecretsConfig.java` |
| Never do network I/O inside a DB transaction/lock unnecessarily | `recordAuthorization` is non-transactional; refund holds the lock deliberately (documented) | A slow gateway can't exhaust the connection pool via idle transactions | `PaymentCompletionService.java:132` |
| Timeouts on every outbound call | 3 s connect / 10 s read | A stalled dependency degrades one request, not the server | `RazorpayGatewayConfig.java:26` |
| Idempotency at every entrance | One lock + status check shared by browser, webhook, reconciliation, refund webhook | At-least-once delivery is safe | `PaymentCompletionService.java:201`, `RefundService.java:100` |
| Secrets never logged | `PaymentGatewayProperties.toString()` masks secrets; startup message never echoes the key; test asserts it | A leaked log must not leak credentials | `PaymentGatewayProperties.java` |
| Money as integer paise | Amounts, fees and tax are `long` paise everywhere | No floating-point rounding bugs | `RazorpayGateway.java:62` |
| Append-only audit | Refund adds `payment_events` (`ADMIN_REFUND`/`WEBHOOK`) and `admin_actions` rows in the same transaction | Reconstruct any refund later | `RefundService.java:127` |
| Test the HTTP client without a network | `MockRestServiceServer` asserts exact requests | Fast, deterministic, no secrets in CI | `RazorpayGatewayTest.java` |
| Known-answer crypto tests | Signature vectors computed independently in Python, not by the code under test | A bug in the code can't make its own test pass | `RazorpaySignaturesTest.java` |
| Graceful degradation for non-essential UI | `DemoBanner` renders nothing without an Apollo client or if the query fails | An informational strip never takes a page down | `DemoBanner.tsx:26` |

---

## 5. What does what — file map

| File | Responsibility |
|---|---|
| `commerce/gateway/PaymentGateway.java` | The port: create order, fetch payment(s), capture, refund |
| `commerce/gateway/RazorpayGateway.java` (+ `RazorpayGatewayConfig`) | Real adapter over `RestClient`; errors → `PAYMENT_GATEWAY_UNAVAILABLE` |
| `commerce/gateway/MockRazorpayGateway.java` | Stateful simulator implementing the whole port (tracks payments, fees, captures, refunds) |
| `commerce/CommerceConfig.java` | Startup guard: unknown provider, mock in prod, live key without `live-enabled` |
| `commerce/service/PaymentCompletionService.java` | Completion path (browser/webhook/reconciliation), authorization→capture, expiry, fee capture |
| `commerce/service/RefundService.java` | Admin refund + `refund.processed` webhook |
| `commerce/service/PaymentReconciliationJob.java` | 10-minute sweep of stuck `PENDING` orders |
| `commerce/service/PlatformInfoService.java` | `MOCK | TEST | LIVE` from provider + key prefix |
| `commerce/controller/RazorpayWebhookController.java` | Now also `payment.authorized` and `refund.processed` |
| `admin/resolver/AdminOrderResolver.java` | `refundOrder` mutation (ADMIN only) |
| `db/migration/V9__razorpay_fees_refunds.sql` | Fee/refund columns, pending-orders index, `REFUND_PROCESSED` notification type |
| `frontend/src/lib/razorpay.ts` | Loads checkout.js once; typed `window.Razorpay` |
| `frontend/src/hooks/useRazorpayCheckout.ts` | Builds the options and wires success / dismiss / failed |
| `frontend/src/hooks/useCheckout.ts` | Drives the page for both providers; shared `submitVerification` |
| `frontend/src/components/layout/DemoBanner.tsx` | Site-wide "Demo mode" strip |
| `frontend/src/pages/buyer/CheckoutPage.tsx` | Razorpay button + test hints, or the mock panel |

**Request trace — paying with real Razorpay (test mode):**
1. Buyer clicks Buy → `initiateOrder` → `RazorpayGateway.createOrder` (`POST /orders`) → order + payment rows → payload includes `provider: RAZORPAY`.
2. Checkout page → `useCheckout.payWithRazorpay` → `loadRazorpay` (script once) → `new Razorpay({key, order_id, amount, …}).open()`.
3. Buyer pays in Razorpay's iframe → `handler(response)` → `verifyPayment` mutation → `PaymentCompletionService.verifyCheckout` checks the HMAC signature.
4. `applyCapture` (order row locked) → `fetchPayment` for fees → payment `COMPLETED` → order `COMPLETED` → `FulfillmentService` grants the entitlement.
5. Meanwhile Razorpay's `payment.captured` webhook arrives → same `applyCapture` → sees `COMPLETED` → no-op.
6. If steps 3 and 5 both never arrive, `PaymentReconciliationJob` finds the captured payment within ~25 minutes and runs step 4.

---

## 6. Design decisions and trade-offs

### Decision: no Razorpay SDK — a small `RestClient` adapter
- **Alternatives considered:** the official `razorpay-java` SDK.
- **Why:** five endpoints; fewer transitive dependencies on a 12 GB ARM box; the exact request is assertable in tests.
- **What we gave up:** we hand-parse JSON and track Razorpay API changes ourselves.
- **Revisit when:** we need many more endpoints (subscriptions, Route) — the SDK's coverage would then win.

### Decision: platform absorbs gateway fees
- **Alternatives:** deduct fees from creators; pass a "convenience fee" to buyers.
- **Why:** simplest to explain and to compute; creators see a clean 90%.
- **Gave up:** on cheap products the fee (~2.36%) eats a real share of the 10% commission, and refunds cost us the fee too.
- **Revisit when:** margins on low-priced items go negative — then a minimum price or shared fee.

### Decision: fee lookup inside the completion lock (best-effort)
- **Alternatives:** fetch the fee before taking the lock; fill it in later from a webhook or the reconciliation job.
- **Why:** fetching before the lock means calling Razorpay with a payment id the *browser* supplied, before we've validated anything (a cheap way for an attacker to make us call the gateway). Inside the lock it only runs after all checks, once per order.
- **Gave up:** the lock is held for up to the gateway timeout on the one completing call. Bounded (3 s + 10 s) and per-order, but real.
- **Revisit when:** fee lookup shows up in latency dashboards — move it to an after-commit step.

### Decision: refunds call the gateway inside the transaction
- **Alternatives:** commit a "refund requested" state, then call the gateway asynchronously (outbox/saga).
- **Why:** admin refunds are rare; holding the order lock is what prevents a double refund; failure rolls back cleanly.
- **Gave up:** if the gateway succeeds and our commit then fails we've refunded without recording it — the `refund.processed` webhook repairs that.
- **Revisit when:** refunds become frequent or bulk.

### Decision: full refunds only
- Partial refunds need per-line accounting, entitlement rules and earnings maths. Out of scope; the order/payment state machine would need a `PARTIALLY_REFUNDED` state.

### Decision: `platformInfo` is public and derived
- **Alternatives:** an environment flag `DEMO_MODE=true`.
- **Why:** a separate flag can disagree with the actual key; deriving mode from the key means the banner is true by construction.

### Decision: one backend key + frontend switches on the provider the server reports
- **Alternatives:** a `VITE_` build-time flag. **Why not:** the deployed backend is the source of truth, and the same frontend build must work against mock (dev) and real (prod).

---

## 7. Interview questions

### Beginner
**Q: What is a payment gateway adapter and why did you write one?**
A: The app depends on a `PaymentGateway` interface with methods like `createOrder` and `refund`. `RazorpayGateway` implements it by calling Razorpay's REST API; the mock implements it in memory. Because business code only knows the interface, moving from the mock to real Razorpay didn't touch order or entitlement logic — that's the whole value of the pattern.

**Q: What's the difference between Razorpay test mode and live mode?**
A: Same API, two key pairs. `rzp_test_` keys move no real money and accept test cards; `rzp_live_` keys are real. We only run test mode until KYC and legal pages exist, and the app refuses to start with a live key unless a separate `live-enabled` flag is set.

**Q: Why do you show a "Demo mode" banner?**
A: So nobody thinks they're being charged — or types a real card into a test deployment. It's driven by the server's payment mode, which is derived from the key prefix, so it can't be wrong about what the server is really doing.

**Q: Why store money as integer paise?**
A: Floating-point can't represent most decimals exactly, so sums drift. Integers of the smallest unit are exact, and Razorpay's API takes paise anyway.

### Intermediate
**Q: How do you test a class that calls an external HTTP API?**
A: `MockRestServiceServer`. It replaces the HTTP transport, so the test asserts the exact request — method, URL, Basic auth header, JSON body — and returns canned responses, including 400s and 500s. No network, no real keys, and it runs in milliseconds.

**Q: Authorize vs capture — what happens if you never capture?**
A: Authorize only reserves the money. If nobody captures, the reservation expires and the money returns to the buyer after a few days: they got nothing and we got nothing. So on `payment.authorized` we call `capture`, which fires `payment.captured`, and *that* completes the order. We do it whether or not the account auto-captures.

**Q: A refund webhook arrives twice, or after the admin already refunded. What happens?**
A: Nothing. The handler locks the order, checks the event id against `payment_events` (unique), and checks the order isn't already `REFUNDED`. Duplicate or late deliveries hit one of those and return without changing anything. I test it: replay the webhook and assert the event, notification and audit row counts are unchanged.

**Q: Why is the guard against live keys a startup failure and not a log warning?**
A: Because nobody reads warnings on a working deploy. A misconfiguration that could charge real cards should be impossible to ship, and a crash at boot with a clear message is the loudest, safest signal.

**Q: What does the reconciliation job do, and why isn't the webhook enough?**
A: Webhooks are best-effort: the server can be down, the network can drop, retries are finite. Every 10 minutes the job asks Razorpay about `PENDING` orders older than 15 minutes; if a payment was captured it completes the order via the same idempotent path, and after 24 hours with nothing captured it marks the order failed.

### Advanced / follow-up probes
**Q: Three things can complete the same order at once — browser callback, webhook, reconciliation. How is that safe?**
A: All three go through one method that first takes `SELECT … FOR UPDATE` on the order row, then checks the status. They serialize; the second sees `COMPLETED` and no-ops. One subtlety we documented: the lock must be the *first* read, because Hibernate returns the already-loaded entity with stale field values if you read then lock. On top of that, a unique index on the active entitlement is a last-resort backstop.

**Q: Why not call the gateway inside a transaction?**
A: An open transaction holds a database connection. If Razorpay is slow, every in-flight request pins a connection while it waits, and the pool empties — a gateway slowdown becomes an outage of everything. So `recordAuthorization` runs outside a transaction and only reads. The exceptions — refund and the fee lookup — are deliberate, bounded by timeouts, and documented as trade-offs, because there the lock is *the point* (prevent double refunds) or the call runs once per order.

**Q: If the Razorpay refund call succeeds but your database commit fails, what state are you in and how does it heal?**
A: Money refunded, our order still `COMPLETED`, entitlement still active. Razorpay then sends `refund.processed`; the webhook path finds the order still `COMPLETED` and applies the same effects. That's why refund has two entrances funnelling into one `applyRefund`.

**Q: Who should absorb gateway fees and how does it affect your data model?**
A: We chose the platform. That means the fee lives on the *payment* (`gateway_fee_paise`, `gateway_tax_paise`) not on the order item, creator earnings stay price minus a snapshotted commission, and platform net is a derived figure. Refunded orders still count their fee, because Razorpay doesn't return it. The alternative — netting fees off creators — would mean earnings depend on a number we only learn after capture.

**Q: How does your CSP still protect you when you allow Razorpay?**
A: By naming exact hosts: `script-src` and `frame-src` for `checkout.razorpay.com`, `connect-src` for `api.razorpay.com`, nothing else. No `unsafe-inline`, no wildcards. Card entry happens inside Razorpay's iframe, so our origin's JavaScript never handles card data.

### "Tell me about a bug you fixed"
**Q: Tell me about a bug you hit on this phase.**
A: *Situation:* I added a demo banner to the shared page layout, which fetches a GraphQL query. *What was wrong:* 26 existing frontend tests failed at once with an Apollo "Could not find client" invariant — many tests render the layout without an Apollo provider. *How I found it:* the failures all pointed at the same invariant, and only started after my change to `AppLayout`. *The fix:* the banner checks whether an Apollo client exists (`getApolloContext`) and renders nothing if not — a non-essential strip shouldn't be able to crash a page. *Lesson:* adding a data dependency to a shared layout component silently changes the requirements of every test and page that uses it; make optional UI degrade rather than throw.

---

## 8. Gotchas and bugs we hit

| Symptom | Root cause | Fix | Lesson |
|---|---|---|---|
| 26 frontend tests failed with an Apollo invariant right after adding the banner | `useQuery` in a component with no `ApolloProvider` throws | `DemoBanner` checks `getApolloContext().client` and renders nothing without one | Shared layout + new data dependency = blast radius across every test |
| Checkout test rendered "Order not found" instead of the payment panel | Apollo `MockedProvider` results lacking the new `gatewayKeyId`/`paymentProvider` fields make the cache write incomplete | Added the fields to the test fixtures | When a query grows, mocked responses must too |
| `react-refresh/only-export-components` lint failure | Exported a constant from a component file | Made the constants file-private, test uses the literal | Component files export components only |
| Duplicate top-level `payment:` key nearly added to `application-test.yml` | Appended a second `payment:` block | Merged into the existing block before running | YAML silently lets the later duplicate key win — one block per key |
| Postgres enum can't just gain a Java value | `notification_type` is a Postgres `ENUM`; a new Java constant fails at insert time | `V9` does `ALTER TYPE … ADD VALUE` (must not be *used* in the same migration) | Enums exist in two places — Java and the database |
| An `Order` GraphQL field needs server config, not order data | The Razorpay key id and provider aren't on the order row | `@SchemaMapping` resolvers on `Order` (`gatewayKeyId` only while `PENDING`, `paymentProvider`) | GraphQL fields can be computed; don't force everything into the DTO |

---

## 9. New vocabulary

| Term | One-line meaning |
|---|---|
| **Port / adapter (hexagonal architecture)** | An interface the core depends on, and the implementations that connect it to the outside world |
| **Test mode / live mode** | Razorpay's two environments: fake money (`rzp_test_`) and real money (`rzp_live_`) |
| **Authorize** | The bank reserves the amount on the buyer's card; nothing is ours yet |
| **Capture** | Claiming an authorized amount so it actually settles to us |
| **Auto-capture** | A Razorpay account setting that captures every authorized payment immediately |
| **Reconciliation** | Comparing our records against the provider's to find and fix disagreements |
| **Refund** | Returning money to the buyer; modelled here as a state change, not a delete |
| **Gateway fee / MDR** | What the payment provider charges the merchant per payment (~2% + GST on the fee) |
| **`MockRestServiceServer`** | Spring test utility that stands in for a remote HTTP server and verifies the requests sent to it |
| **Basic auth** | An HTTP header carrying `base64(user:password)` — here `key_id:key_secret` |
| **`@ConstructorBinding`** | Tells Spring Boot which constructor of a properties record to bind config into |
| **Receipt** | Our own reference (`sl_order_42`) sent to the gateway and echoed back — the gateway-side idempotency handle |

---

## 10. If I had to defend this in a code review

**Strongest points**
- The swap really was an adapter swap: order and entitlement logic gained features, not rewrites, and the mock implements the *same* port, so integration tests exercise production code paths.
- Every route that can complete or reverse a payment is idempotent and lock-serialized, and each has a test that replays it.
- Dangerous states are made unreachable rather than documented: live key without the switch, mock in prod, blank secrets all fail at startup.

**Weakest points (and what I'd fix first)**
- **`fetchPayment` runs while holding the order lock**, and the admin refund calls Razorpay under the lock. Both are bounded by timeouts and justified above, but a slow Razorpay makes those requests slow and ties up a DB connection. Fix: move the fee lookup after commit; convert refunds to a `REFUND_PENDING` state processed asynchronously (outbox).
- **Reconciliation is a polling loop over a bounded batch (100 per sweep)** with no metrics. If thousands of orders go stale it lags and nobody is alerted. Fix: expose a gauge of "orders stuck > 1 h" (Phase 10 observability).
- **The mock's behaviour is my reading of Razorpay's docs**, not Razorpay itself. `RazorpayGatewayTest` pins the request shapes, but nothing here has been run against the real test-mode API from CI — that needs the owner's real test keys (see the PR's "could not verify").
- **Docs conflict flagged, not silently resolved:** `docs/requirements.md` PAY-02 still says "Mock payment provider for MVP", and `docs/deployment.md` still described Render/Supabase/Vercel with `PAYMENT_GATEWAY=mock`; ADR 0001 moved hosting to one Oracle server. I updated the payment sections of the runbook and left the hosting sections for Phase 09D.
