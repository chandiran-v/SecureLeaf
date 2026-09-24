# Phase 04 — Commerce (orders → payments → entitlements)

> **Status:** Done
> **Built:** 2026-09-23
> **Requirement IDs covered:** PAY-01 … PAY-10 (`docs/requirements.md` Module 4), plus the first slice of LIB-01/02 (My Library)
> **Design doc:** [`docs/phase4_implementation_prompt.md`](../phase4_implementation_prompt.md) — decisions D1–D13 are referenced throughout
> **Commits:** *(uncommitted at time of writing)*

---

## How to study this note

Read it in order, once. Then, before an interview, go straight to **section 7** and answer every question *out loud* before reading the answer. The questions climb from "what is idempotency?" to "design a payment system that never double-charges". If you can answer section 7 without looking, you can defend this phase in any interview.

---

## 1. What we built, in plain English

A buyer can now actually **buy** something. On a product page they click **Buy for ₹499**. The site creates an *order* (a record saying "this person wants this product at this price"), then sends them to a checkout page. There they pay through a pretend payment company that behaves exactly like **Razorpay**, India's most common payment gateway. Once the payment is confirmed, the buyer gets an **entitlement**: a permanent "this person may read this product" record. The product appears in their new **My Library** page, both buyer and creator get a notification (in the app and by email), and the creator's dashboard shows their earnings after SecureLeaf's 10% cut.

The hard part isn't the happy path. It's that **payments go wrong in ways normal features don't.** A buyer double-clicks. The network drops *after* the money was taken but *before* we heard about it. The payment company tells us the same thing twice, or out of order, or seconds late. A hacker POSTs a fake "payment succeeded" message to our server. Two of these happen in the same millisecond. Every one of them has to end in the same place: **charged once, granted once, recorded once.**

The checkout page has three buttons (**Pay**, **Simulate decline**, **Simulate timeout**) so you can trigger each of those scenarios yourself and watch the system handle it.

**Before this phase:** products could be browsed and previewed, but the Buy button was disabled. No one could own anything.
**After this phase:** buying works end to end for paid and free products. It is safe against double-clicks, retries, duplicate or out-of-order payment notifications, forged payment messages and concurrent requests. Every money movement leaves a tamper-proof audit trail.

---

## 2. Why it matters

- **It's the business.** A marketplace that can't take money is a catalogue.
- **Phase 5 depends on it.** The secure viewer (Phase 5) will ask one question before serving a single page: *does this user hold an ACTIVE entitlement for this product?* (VIEW-12). This phase creates that record.
- **It's where correctness stops being optional.** A bug in search shows the wrong books. A bug here charges someone twice, or gives a product away for free, and both are visible, costly and hard to undo. That is why this phase spends more code on *what can go wrong* than on *what should happen*.
- **It is the most-asked-about area in backend interviews.** "How do you prevent double charges?" and "How do you handle webhooks?" come up constantly, because they test concurrency, databases, distributed systems and security at once.

**What would break if we skipped the hard parts:** a double-click would create two orders and could charge twice. A slow network would show "payment failed" to someone who was charged. A duplicated webhook would count the sale twice. Anyone who found the webhook URL could give themselves free products.

---

## 3. New concepts introduced

### 3.1 Idempotency

**What it is:** an operation is *idempotent* if doing it twice has the same effect as doing it once. (You met this word in Phase 1; this phase is where it becomes the main event.)

**The analogy:** a lift's call button. Pressing it five times doesn't summon five lifts. Pressing a "pay ₹499" button five times must not charge ₹2,495.

**Why we needed it here:** in a distributed system, **every message can arrive more than once.** The browser retries after a timeout. The user double-clicks. Razorpay deliberately re-sends webhooks until we acknowledge them. You cannot stop duplicates from *arriving*; you can only make them *harmless*.

**How it works:** there are two classic techniques, and we use both.
1. **Remember what you've already done, keyed by a unique ID.** If the same ID arrives again, return the stored result instead of redoing the work. Examples are the *idempotency key* (3.2) and the webhook event ID (3.9).
2. **Make the operation naturally a no-op the second time.** "Set the order to COMPLETED; if it's already COMPLETED, do nothing" (3.4).

**In our code:** `PaymentCompletionService.java:140`
```java
if (order.getStatus() == OrderStatus.COMPLETED) {    // ← the other messenger got here first
    log.info("Order {} already completed — {} is a no-op", order.getId(), source);
    return order;
}
```

**What breaks without it:** double charges, double entitlements, a sales counter that says 2 when one copy sold.

---

### 3.2 Idempotency keys (the Stripe pattern)

**What it is:** a unique random string (a UUID) that the **client** generates for one purchase attempt and sends with the request. The server stores it with the result. If a request arrives with a key it has seen before, the server returns the **original result** instead of creating something new.

**The analogy:** a cheque number. If the bank sees cheque #1042 twice, it cashes it once and knows the second one is a duplicate. It can tell a duplicate apart from a legitimate second cheque *for the same amount*.

**Why we needed it here:** `initiateOrder` creates an order. Without a key, the server can't tell "the user double-clicked" from "the user genuinely wants to start a second purchase".

**How it works:**
1. When the product page loads, the browser creates one UUID and keeps it for the whole visit (`useState` initializer, `useBuyProduct.ts`).
2. Every click sends that same key. A double-click or a retry after a network error sends it again.
3. The server stores it in `orders.idempotency_key` (UNIQUE). On a repeat, it finds the existing order and returns it (`OrderService.java:73`).
4. If the same key comes back for a **different product**, that's a client bug. We refuse with `IDEMPOTENCY_KEY_REUSED` rather than return a mismatched order.

**In our code:** `frontend/src/hooks/useBuyProduct.ts`
```ts
const [idempotencyKey] = useState(newIdempotencyKey);   // once per page visit, NOT per click
```

**What breaks without it:** creating the key *inside* the click handler looks harmless, but each click would then look like a brand-new purchase and the protection would be gone. Where you generate the key matters as much as having one.

**Second hop:** our server also has a key *toward the gateway*. It's `payments.idempotency_key = "sl_order_<id>"`, sent as Razorpay's `receipt`. Idempotency has to hold at every hop: client → us → gateway.

---

### 3.3 Race conditions and "check-then-act"

**What it is:** a *race condition* is a bug where the result depends on the exact timing of two things running at once. The most common shape is **check-then-act**: "if there's no open order, create one". It's correct when one request runs, and wrong when two run together, because both check before either acts.

**The analogy:** two people look at the last seat on a bus timetable app, both see "1 seat left", and both book it.

**Why we needed it here:** two tabs, or a double-click that fires two HTTP requests 5 ms apart, both run `initiateOrder`. Both look for an open order, both find none, both create one. The idempotency key doesn't help here, because the two tabs have **different** keys.

**How it works (the timeline that goes wrong):**
```
Request A: SELECT open orders → none
Request B: SELECT open orders → none        ← B checks before A has inserted
Request A: INSERT order #1
Request B: INSERT order #2                  ← duplicate
```
The fix is to make "check, then act" **atomic** with a lock (3.4).

**What breaks without it:** duplicates that only appear under load, never on your laptop, and are nearly impossible to reproduce. That's why `CommerceIT` fires 10 concurrent requests on purpose (`concurrentInitiates_fromOneBuyer_allGetTheSameOrder`).

---

### 3.4 Pessimistic locking (`SELECT … FOR UPDATE`)

**What it is:** asking the database to **lock a row** as you read it. Any other transaction that tries to lock the same row **waits** until yours commits or rolls back. "Pessimistic" means we assume a conflict will happen and prevent it up front.

**The analogy:** a single-occupancy bathroom. You lock the door when you go in; the next person waits outside; they get in only after you leave, and they see the room as you left it.

**Why we needed it here:** in two places.
- **Per-buyer lock during checkout (D2):** `initiateOrder` locks the buyer's own `users` row first. A second request from the same buyer waits, then sees the first one's order. Other buyers lock *different* rows, so they're never slowed down.
- **Per-order lock during payment completion (D3):** the browser's confirmation and Razorpay's webhook often arrive within milliseconds of each other. Both lock the order row, so they run one after the other. The second sees `COMPLETED` and does nothing.

**In our code:** `UserRepository.java:40` and `OrderRepository.java:52`
```java
@Lock(LockModeType.PESSIMISTIC_WRITE)                 // → SQL: SELECT … FOR UPDATE
@Query("select o from Order o where o.gatewayOrderId = :gatewayOrderId")
Optional<Order> findByGatewayOrderIdForUpdate(@Param("gatewayOrderId") String gatewayOrderId);
```

**Pessimistic vs optimistic locking.** Interviewers love this comparison.
| | Pessimistic (`FOR UPDATE`) | Optimistic (`@Version`) |
|---|---|---|
| Idea | Block others while I work | Let everyone work; the loser's write is rejected at commit |
| Conflict result | Second request **waits**, then proceeds with fresh data | Second request **fails** (`OptimisticLockException`) and must retry |
| Best when | Conflicts are likely, and failing is expensive (a paid order!) | Conflicts are rare, and retrying is cheap |
| Used here | Checkout + payment completion | `Product.@Version` (Phase 0 schema) — which is exactly why we *don't* increment sales through the entity (3.7) |

**What breaks without it:** the race in 3.3, and double fulfilment when browser and webhook arrive together.

> **Learning moment: lock first, then read.** See Gotchas row 2. Loading the order normally and *then* locking it looks equivalent but isn't: Hibernate returns the already-loaded object with its **old** field values.

---

### 3.5 State machines

**What it is:** a model where a thing is always in exactly one **state** from a fixed list, and only certain **transitions** between states are legal. Anything else is a bug and must be rejected.

**The analogy:** a parcel's tracking status. It goes *Ordered → Shipped → Delivered*. It can never go from *Delivered* back to *Ordered*, and if the system tries, something is badly wrong.

**Why we needed it here:** order and payment status drive money and access. An order that silently moves `COMPLETED → PENDING` could be paid for twice; a `REFUNDED → COMPLETED` jump would restore access without payment.

**How it works:**
```
Order:    PENDING ──► COMPLETED ──► REFUNDED
             └──────► FAILED            (superseded — e.g. price changed)

Payment:  PENDING ──► COMPLETED ──► REFUNDED
             │            ▲
             ▼            │
           FAILED ────────┘              (retry succeeded on the same Razorpay order)
             │ ▲
             └─┘                         (another declined attempt)
```
Note the design choice: **a declined card does *not* fail the order.** Razorpay lets a buyer retry on the same order, so only the *payment* records the failure (D5).

**In our code:** `PaymentStatus.java:24` and `Order.java:54`
```java
public boolean canTransitionTo(PaymentStatus next) {
    return switch (this) {
        case PENDING, FAILED -> next == COMPLETED || next == FAILED;
        case COMPLETED -> next == REFUNDED;
        case REFUNDED -> false;
    };
}
```
`Order.status` and `Payment.status` have **no public setter** (`@Setter(AccessLevel.NONE)`). The only way to change them is `transitionTo(...)`, which checks the table. Illegal moves throw `INVALID_STATE_TRANSITION`.

**What breaks without it:** status logic spreads across `if` statements in five services, and one of them eventually does something illegal without anyone noticing.

---

### 3.6 Append-only audit log

**What it is:** a table you only ever **add** rows to, never update or delete. Each row records one event: "payment 12 moved from PENDING to COMPLETED at 10:04:31, reported by the webhook, here's the raw message".

**The analogy:** a bank passbook. Mistakes aren't erased. A correcting entry is added below, so the full history is always readable.

**Why we needed it here (PAY-06):** when a customer says "I was charged but didn't get the book", you need to reconstruct exactly what happened. When a payment provider disputes a charge, you need evidence. A status column tells you *where you are*; an append-only log tells you *how you got there*.

**How it works:**
1. `PaymentAuditService.transition()` is the **only** way a payment's status changes, and it always appends a `payment_events` row in the same call (`PaymentAuditService.java:45`). You can't do one without the other.
2. The **database** enforces append-only with a trigger. Even a developer with SQL access, or a buggy future service, can't edit history. `V4__commerce_integrity.sql:44`:
```sql
CREATE TRIGGER trg_payment_events_append_only
    BEFORE UPDATE OR DELETE ON payment_events
    FOR EACH ROW EXECUTE FUNCTION forbid_payment_event_mutation();   -- RAISE EXCEPTION
```

**What breaks without it:** "the logs say it was fine" when the logs were overwritten. You end up in disputes you can't win.

---

### 3.7 Lost updates and atomic increments

**What it is:** a *lost update* happens when two transactions read the same value, both change it, and the second write erases the first. It's read-modify-write under concurrency.

**The analogy:** two cashiers each read "stock: 5" from a notebook, each sells one, and each writes "4". Two sold, but the notebook says one did.

**Why we needed it here:** `products.total_sales` goes up by one per sale. Doing it in Java with `setTotalSales(getTotalSales() + 1)` is read-modify-write. It *also* bumps `Product.@Version`, so the second concurrent buyer would hit an optimistic-lock exception, and **a purchase they already paid for would roll back.**

**How it works:** let the database do the arithmetic in one statement. Postgres locks the row for the instant of the update, so concurrent increments queue up and none are lost. `ProductRepository.java:59`:
```java
@Modifying(flushAutomatically = true)
@Query("update Product p set p.totalSales = p.totalSales + 1 where p.id = :id")
int incrementTotalSales(@Param("id") Long id);
```

**What breaks without it:** sales counts that drift below reality, or, worse here, paid purchases rolled back by an optimistic-lock failure.

---

### 3.8 Transactions and "all or nothing" fulfilment

**What it is:** a *transaction* groups several database changes so they either **all** happen or **none** do (the "A", atomicity, in ACID). `@Transactional` in Spring starts one when a method begins and commits it when the method returns, or rolls it back if it throws.

**The analogy:** a bank transfer. Money leaving account A and arriving in account B is one indivisible thing. You never want "left A, never arrived at B".

**Why we needed it here:** completing a purchase touches six things: the payment status, a payment event, the order status, a new entitlement, the sales counter and two notification rows. Every one of them happens in **one transaction**, so there's no state where "the order says COMPLETED but no entitlement exists".

**How it works:** `FulfillmentService.fulfil()` is annotated `@Transactional(propagation = MANDATORY)` (`FulfillmentService.java:38`). *MANDATORY* means "I must be called inside an existing transaction, and I refuse to start my own." If someone calls it without one, Spring throws immediately instead of committing half a purchase.

**Transaction propagation:** interviewers ask about these.
| Propagation | Meaning |
|---|---|
| `REQUIRED` (default) | Join the caller's transaction, or start one if none exists |
| `MANDATORY` | Must join an existing one, otherwise throw. We use this for "part of a bigger unit" methods |
| `REQUIRES_NEW` | Always start a fresh one, pausing the caller's (e.g. audit logs that must survive a rollback) |

> **Learning moment: the self-invocation trap.** `@Transactional` works through a **proxy**, a wrapper object Spring puts around your bean. A call from one method to another *on the same object* skips the proxy, so an annotation on the inner method is silently ignored. That's why `verifyCheckout` is itself `@Transactional` rather than relying on an annotation on `applyCapture` (`PaymentCompletionService.java` class comment).

---

### 3.9 Webhooks and at-least-once delivery

**What it is:** a *webhook* is an HTTP request **the payment company sends to us** when something happens ("payment captured"). It is the reverse of a normal API call: they call us.

**The analogy:** a courier's delivery notification. You don't keep phoning the courier to ask whether the parcel has arrived; they message you. And if you don't reply "got it", they message again.

**Why we needed it here:** the browser is an **unreliable messenger.** The buyer can close the tab one second after paying, the phone can lose signal, or the gateway can time out on the browser while still taking the money. The webhook is server-to-server and retried until acknowledged, so it is the **source of truth** (D4, D13).

**How it works:**
- **At-least-once delivery:** providers resend a webhook until they get a `2xx` reply. That guarantees you'll hear about every payment, and also that **you will sometimes hear about the same payment twice.** "Exactly once" delivery over a network isn't achievable; you get *effectively-once* by combining at-least-once delivery with idempotent processing.
- **Dedup by event ID:** every Razorpay event has an ID (`X-Razorpay-Event-Id`). We store it in `payment_events.provider_event_id`, which is **UNIQUE**, and skip events we've already recorded (`PaymentCompletionService.java:181`).
- **Response codes are instructions:** `2xx` means "stop retrying". So we return 200 for successes **and** duplicates **and** hopeless business errors (unknown order, amount mismatch), because retrying can't fix them. We return 5xx only for *our* transient failures, such as the database being down, where a retry should succeed (`RazorpayWebhookController.java` class comment).
- **Out-of-order delivery:** a `payment.failed` for an *earlier* attempt can arrive *after* the successful one. We ignore failures for orders that are no longer PENDING (`PaymentCompletionService.java:112`).

**The mock proves it:** `payment.mock.duplicate-webhooks: true` makes the mock gateway send **every** webhook twice (`MockWebhookSender.java:85`). If dedup were broken, you'd see double sales in dev.

**What breaks without it:** trusting only the browser loses payments from closed tabs. Processing webhooks without dedup counts sales twice. Returning 500 on a duplicate triggers a retry storm.

---

### 3.10 HMAC signatures (and why not a plain hash)

**What it is:** an **HMAC** (Hash-based Message Authentication Code) is a fingerprint of a message computed **with a secret key**. Only someone holding the key can produce the right fingerprint, so a valid HMAC proves both that the message wasn't altered **and** who sent it.

**The analogy:** a wax seal made with a signet ring. Anyone can see the seal, but only the ring's owner can make it.

**Why we needed it here:** our webhook URL is public (`permitAll` in `SecurityConfig`), because Razorpay has no JWT. Without a signature check, anyone could `curl` a fake `payment.captured` and get products for free. The same applies to the browser's `verifyPayment` call: the browser could simply *claim* it paid.

**How it works:** Razorpay's two schemes, implemented exactly as documented (`RazorpaySignatures.java`):
- **Checkout signature:** `HMAC_SHA256(order_id + "|" + payment_id, key_secret)`. The browser relays it, and we recompute and compare (`PaymentCompletionService.verifyCheckout`).
- **Webhook signature:** `HMAC_SHA256(raw_body, webhook_secret)`, sent in the `X-Razorpay-Signature` header.

**Three details that are classic interview follow-ups:**
1. **Why not SHA-256 of the body?** Anyone can compute a plain hash, so it proves integrity but not *authenticity*. The secret is what makes it proof of origin.
2. **Sign the raw bytes.** If you parse the JSON and re-serialise it, whitespace and key order can change and a genuine signature fails. The controller takes `@RequestBody byte[]` (`RazorpayWebhookController.java:59`).
3. **Constant-time comparison.** `String.equals` stops at the first different character, so its timing leaks how many leading characters were right, and an attacker can guess one character at a time. `MessageDigest.isEqual` always compares every byte (`RazorpaySignatures.java:50`).

**What breaks without it:** free products for anyone who reads your frontend's network tab.

---

### 3.11 The Strategy / Adapter pattern for the payment gateway

**What it is:** we put an interface, `PaymentGateway`, in front of "the payment company", so the rest of the code never knows *which* company it is. (Strategy: swap implementations at runtime or by config. Adapter: wrap a third-party API in *your* interface.)

**The analogy:** a universal travel plug adapter. Your laptop doesn't care which country's socket is in the wall.

**Why we needed it here:** you asked to integrate real Razorpay later. The interface is deliberately tiny: in Razorpay's flow, our server only ever *creates an order*. The card is charged between the browser and Razorpay, and the result comes back through the signed checkout response and webhook, which `RazorpaySignatures` verifies with the real algorithm. **Swapping in the real Razorpay means one new class (`RazorpayGateway`), your keys, and loading `checkout.js` on the frontend.** `OrderService` and `PaymentCompletionService` don't change.

**In our code:** `PaymentGateway.java`, `MockRazorpayGateway.java`, `MockRazorpayCheckoutController.java` (stands in for Razorpay's servers and checkout popup), `MockWebhookSender.java` (stands in for Razorpay's webhook system).

**What breaks without it:** payment-provider calls scattered across services. Switching providers, or even testing without one, becomes a rewrite.

---

### 3.12 Money: paise, basis points, and snapshots

**What it is:** three rules for handling money correctly.
1. **Integers in the smallest unit (paise).** `0.1 + 0.2 = 0.30000000000000004` in floating point, and money must never drift like that.
2. **Rates in basis points.** 1 bps = 0.01%, so 10% = `1000`. The fee maths stays pure integer (`CommerceProperties.java:23`):
   ```java
   return (pricePaise * platformFeeBps + 5_000) / 10_000;   // round half-up to the nearest paisa
   ```
3. **Compute one side, derive the other by subtraction.** `creatorEarnings = price − fee` (`OrderService.java:171`). If both halves were rounded independently, a paisa could appear or vanish. A DB `CHECK (platform_fee_paise + creator_earnings_paise = price_paise)` enforces it.

**Snapshot at purchase time (D7):** the fee split is **stored on the order item** when the order is created. If SecureLeaf changes its commission to 12% next year, last year's earnings must still say 10%. Recomputing history from today's settings is a classic bug. The old dashboard had exactly that bug: it computed revenue as `totalSales × today's price` (Gotchas row 6).

**Why the `Long` GraphQL scalar:** GraphQL's `Int` is **32-bit** (max 2,147,483,647 paise ≈ ₹2.14 crore). A successful creator's lifetime earnings can exceed that, so `CreatorEarnings` uses a 64-bit `Long` scalar.

---

### 3.13 Database constraints as the last line of defence

**What it is:** rules the **database** enforces, so they hold even when the application code is wrong.

**Why we needed it here:** all the locks and checks above live in Java, and Java code changes. A future developer might add a "gift a product" feature that forgets the ownership check. The database is the one place every write passes through.

**How it works:** V4 adds a **partial unique index**, which is unique only over rows matching a `WHERE` clause (`V4__commerce_integrity.sql:11`):
```sql
CREATE UNIQUE INDEX uq_entitlements_buyer_product_active
    ON entitlements (buyer_id, product_id)
    WHERE status = 'ACTIVE';
```
V1 had `UNIQUE (order_id, product_id)`, which still allowed **two different orders** to grant the same buyer the same product. The partial index says "at most one *active* grant per buyer per product", while still allowing a re-purchase after a revoke. `CommerceIT.secondActiveEntitlement_isImpossible_evenBypassingTheApp` proves it by inserting with raw SQL.

**Defence in depth, for one rule ("never grant a product twice"):**
1. UI: the Buy button becomes "In your library".
2. Service: `ALREADY_OWNED` check.
3. Locks: per-buyer and per-order.
4. Database: the partial unique index.

---

### 3.14 Side effects after commit (and the Outbox pattern)

**What it is:** some actions can't be undone, such as sending an email or publishing a message. They must only run **after** the database transaction has definitely committed.

**The analogy:** don't post the "thanks for your payment!" letter until the bank has actually cleared the cheque.

**Why we needed it here (PAY-08, D9):** if the purchase transaction rolls back, for example because the amount didn't match, an email already sent saying "purchase confirmed" would be a lie you can't take back.

**How it works:**
1. Inside the purchase transaction, `NotificationService` inserts notification rows. They commit or roll back **with** the purchase. It then publishes a Spring event (`NotificationService.java:83`).
2. `NotificationDispatcher` listens with `@TransactionalEventListener(phase = AFTER_COMMIT)` (`NotificationDispatcher.java:46`). Spring **holds** the event and only delivers it if the transaction commits, and silently drops it on rollback. `NotificationIT.rolledBackPurchase_publishesNothing` proves this.
3. `@Async("notificationExecutor")` runs it on a separate thread pool, so a slow SMTP server never delays the buyer's "payment confirmed" response. It's a separate pool from PDF processing, a **bulkhead**: one workload can't starve the other.

**The honest gap and the pro fix:** if the server crashes *between* the commit and the email, the email is never sent. The in-app notification row survives, but the email is lost. The industry-standard fix is the **Transactional Outbox pattern**: write an "outbox" row in the same transaction, then have a separate poller send it and mark it done, retrying until it succeeds. That's listed in section 10.

---

### 3.15 Redis Pub/Sub

**What it is:** *publish/subscribe*. Publishers send a message to a named **channel**, and every client **currently** subscribed receives it. Redis stores nothing; if nobody's listening, the message is gone.

**The analogy:** a radio broadcast, not a letterbox.

**Why we needed it here (PAY-08):** it's the real-time "ping, something new happened" signal. We publish to `notifications:user:{id}`. Because Pub/Sub stores nothing, the **notifications table** is the source of truth and Pub/Sub is only the doorbell.

**Honest status:** nothing subscribes yet. Phase 6 adds a Server-Sent-Events endpoint that bridges Redis to the browser. Until then, the bell polls `myNotifications` every 30 s. Publishing now makes Phase 6 purely additive. In tests, a `@Primary` `RecordingNotificationPublisher` replaces Redis, the same trick as `InMemoryStorageService`.

---

### 3.16 `@BatchMapping` (DataLoader) — N+1 in a new costume

**What it is:** a GraphQL mechanism that collects **every** object in a response needing a field, then resolves that field for all of them in **one** call.

**Why we needed it here (D11):** `Product.ownedByMe` tells the page "you already own this". A normal field resolver (`@SchemaMapping`) runs **once per product**, so a 20-product marketplace page would fire 20 entitlement queries. That's Phase 3's N+1 problem again. `@BatchMapping` runs once, with one `WHERE product_id IN (…)` query (`ProductOwnershipResolver.java:35`).

**Two rules:** the batch method must return results **in the same order as its input keys**, and anonymous visitors get `false` for everything without running any query at all.

---

### 3.17 BOLA → 404, and fail-fast configuration

- **BOLA (Phase 1 concept, new places):** every order and notification lookup includes the caller's ID in the `WHERE` clause (`findByIdAndBuyerId`, `findByIdAndRecipientId`). Someone else's order ID returns **NOT_FOUND**, not FORBIDDEN, so an attacker can't even confirm it exists. Even a *valid* payment signature can't complete someone else's order into your account (`CommerceIT.anotherBuyersOrder_isNotFound_forReadAndVerify`).
- **Fail fast (D12):** the mock gateway's endpoint would mark anything as paid. `application-prod.yml` sets the provider to `razorpay`, which doesn't exist yet, so `CommerceConfig` **refuses to start** (`CommerceConfig.java:36`). A crash at deploy time is far better than free purchases in production.

---

## 4. Best practices applied

| Practice | What we did | Why it matters | Where |
|---|---|---|---|
| Client idempotency keys | UUID per purchase attempt, stored UNIQUE | Retries and double-clicks return the same order | `OrderService.java:73` |
| Lock before check | `FOR UPDATE` on buyer / order row first | Makes check-then-act atomic | `OrderService.java:69`, `PaymentCompletionService.java:71,136` |
| Single completion path | Browser + webhook → `applyCapture` | One place to get right; one no-op rule | `PaymentCompletionService.java:134` |
| Verify, don't trust | HMAC on checkout + webhook; amount check | Stops forged "I paid" messages | `RazorpaySignatures.java`, `PaymentCompletionService.java:151` |
| Constant-time compare | `MessageDigest.isEqual` | Closes timing attacks | `RazorpaySignatures.java:50` |
| Encapsulated state | No status setters; `transitionTo` only | Illegal transitions impossible to write by accident | `Order.java:54`, `Payment.java:54` |
| Audit + transition together | `PaymentAuditService.transition` | Log can't drift from reality | `PaymentAuditService.java:45` |
| DB-enforced invariants | Partial unique index, sum CHECK, append-only trigger | Holds even when Java is wrong | `V4__commerce_integrity.sql` |
| Atomic counters | SQL `x = x + 1` | No lost updates, no version conflicts | `ProductRepository.java:59` |
| Money as integers | Paise, basis points, derive-by-subtraction | No floating-point drift, no lost paisa | `CommerceProperties.java:23` |
| Snapshot history | Fee split stored per order item | Future rate changes don't rewrite the past | `OrderService.java:159` |
| After-commit side effects | `@TransactionalEventListener(AFTER_COMMIT)` | Never email about a rolled-back purchase | `NotificationDispatcher.java:46` |
| Bulkheads | Separate `notificationExecutor` pool | Slow SMTP can't starve PDF processing | `AsyncConfig.java` |
| No third-party token leak | Separate axios instance for the gateway | Our JWT never goes to Razorpay | `frontend/src/lib/mockGateway.ts` |
| Fail fast on bad config | Prod refuses to start with an unimplemented gateway | Mock can't reach production | `CommerceConfig.java:36` |
| Test the failure modes | Concurrency, duplicates, forgery, out-of-order, rollback | Payment bugs only show under these | `CommerceIT`, `NotificationIT` |

---

## 5. What does what — file map

| File | Responsibility |
|---|---|
| `db/migration/V4__commerce_integrity.sql` | Partial unique index, idempotency/gateway columns, fee snapshot + CHECK, append-only trigger |
| `commerce/entity/OrderStatus.java`, `PaymentStatus.java` | State machines (`canTransitionTo`) |
| `commerce/entity/Order.java`, `Payment.java` | `transitionTo` — the only way to change status |
| `commerce/service/OrderService.java` | `initiateOrder` (3 idempotency layers), `getOrderForBuyer`, `creatorEarnings` |
| `commerce/service/PaymentCompletionService.java` | `verifyCheckout` (browser), `recordCapture`/`recordFailure` (webhook), the single `applyCapture` path |
| `commerce/service/FulfillmentService.java` | Entitlement + sales counter + notifications, all in the caller's transaction |
| `commerce/service/PaymentAuditService.java` | Status change + audit event, always together |
| `commerce/service/EntitlementService.java` | My Library; owned-product lookup for `ownedByMe` |
| `commerce/gateway/PaymentGateway.java` | Strategy interface (swap point for real Razorpay) |
| `commerce/gateway/RazorpaySignatures.java` | Razorpay's HMAC schemes + constant-time compare |
| `commerce/gateway/MockRazorpayGateway.java` | Fake Orders API (in memory) |
| `commerce/gateway/MockRazorpayCheckoutController.java` | Fake checkout: SUCCESS / DECLINE / TIMEOUT |
| `commerce/gateway/MockWebhookSender.java` | Fake webhook delivery: signed, delayed, duplicated |
| `commerce/controller/RazorpayWebhookController.java` | Receives webhooks; signature, dedup, response codes |
| `commerce/resolver/CommerceResolver.java` | GraphQL: `initiateOrder`, `verifyPayment`, `order`, `myLibrary`, `creatorEarnings` |
| `commerce/resolver/ProductOwnershipResolver.java` | `Product.ownedByMe` via `@BatchMapping` |
| `commerce/CommerceConfig.java` | Property binding + fail-fast gateway check |
| `notification/service/NotificationService.java` | Rows in-transaction; `myNotifications`, `markRead` |
| `notification/service/NotificationDispatcher.java` | After-commit, async: Redis publish + email |
| `notification/service/RedisNotificationPublisher.java` | Pub/Sub to `notifications:user:{id}` |
| `frontend/src/hooks/useBuyProduct.ts` | Idempotency key lifetime; initiate → checkout or library |
| `frontend/src/hooks/useCheckout.ts` | Pay → verify; on ambiguity, poll the server |
| `frontend/src/lib/mockGateway.ts` | Stand-in for Razorpay `checkout.js` |
| `frontend/src/pages/buyer/CheckoutPage.tsx`, `LibraryPage.tsx` | Checkout UI; My Library |
| `frontend/src/components/marketplace/BuyPanel.tsx` | The five Buy-button states |
| `frontend/src/components/layout/NotificationBell.tsx` | Bell + unread badge (polling until Phase 6) |

**Request trace: a paid purchase, happy path**
1. `BuyPanel` → `useBuyProduct.buy()` sends `initiateOrder(productId, idempotencyKey)` →
2. `CommerceResolver.initiateOrder` (`@PreAuthorize isAuthenticated`) →
3. `OrderService.initiateOrder`: lock buyer row → replay? → LIVE / not own / not owned → reuse open order? → create order + item (fee snapshot) → `PaymentGateway.createOrder` → payment PENDING + audit event →
4. The browser navigates to `/checkout/:orderId`. The buyer clicks **Pay**, and `mockGateway.payWithMockGateway` POSTs to `MockRazorpayCheckoutController` → returns `{razorpay_order_id, razorpay_payment_id, razorpay_signature}` and schedules a signed webhook →
5. `useCheckout` sends `verifyPayment(input)` → `PaymentCompletionService.verifyCheckout`: lock order → owner check → HMAC check → `applyCapture` → payment COMPLETED + event → order COMPLETED → `FulfillmentService.fulfil` (entitlement, `total_sales + 1`, notification rows) → **commit** →
6. After commit: `NotificationDispatcher` publishes to Redis and sends email (Mailpit) →
7. About 0.8 s later the webhook arrives twice. `RazorpayWebhookController` → HMAC ✓ → `recordCapture` → lock order → already COMPLETED → no-op → 200. Then the duplicate → same → 200.

**Request trace: the TIMEOUT button (D13).** Step 4 returns **504**, but the mock *has* captured the money and schedules the webhook for 4 s later. `useCheckout` shows "don't pay again" and polls `order(id)` every 2 s. The webhook alone completes the order, and the page flips to "Payment confirmed". The browser never had to succeed.

---

## 6. Design decisions and trade-offs

### Decision: pessimistic locks rather than optimistic locking or unique-constraint-only
- **Alternatives considered:** (a) optimistic locking with `@Version` on `Order` and retry on conflict; (b) no locks, relying on unique constraints and catching the violation; (c) a distributed lock in Redis.
- **Why we chose this:** payment completion is exactly where "the loser fails" is unacceptable, because someone has paid. `FOR UPDATE` makes the loser *wait* and then see the truth. Contention is tiny (one buyer, one order), so waiting costs microseconds. Postgres is already our source of truth, so a Redis lock would add a second system that could disagree with it.
- **What we gave up:** a lock held during `initiateOrder` also covers the gateway's `createOrder` call. With real Razorpay that's a network call (~200 ms) holding the buyer's row lock. It only blocks that same buyer's concurrent checkouts, which is acceptable, but it's the textbook "don't do I/O inside a transaction" smell.
- **When we would revisit:** if gateway latency became a problem, split it into (1) a transaction that creates the order, (2) the gateway call with no transaction, and (3) a transaction that stores the gateway ID.

### Decision: the webhook is the source of truth; the browser callback is a fast path
- **Alternatives considered:** browser-only confirmation (simple, and wrong when tabs close); webhook-only (correct, but the buyer waits for the webhook to see success).
- **Why we chose this:** both, converging on one idempotent `applyCapture`. The browser gives instant feedback; the webhook guarantees completion.
- **What we gave up:** two entry points means we need the locking and dedup machinery. That's the price of correctness.

### Decision: a declined card leaves the order PENDING
- **Alternatives considered:** mark the order FAILED on the first decline, so the buyer starts a new order.
- **Why we chose this:** it matches Razorpay's model, where one order allows many payment attempts. It also avoids piles of dead orders. Only the payment records the failure, as its own audit event.
- **What we gave up:** stale PENDING orders accumulate if people abandon checkout (see section 10).

### Decision: free products create a ₹0 COMPLETED order with no payment row
- **Alternatives considered:** grant the entitlement directly with no order, or create a ₹0 payment row.
- **Why we chose this:** `entitlements.order_id` is NOT NULL, and "every grant traces back to an order" is a valuable invariant for audits. No money moved, so a payment row would be fiction.
- **What we gave up:** reports must remember that some orders have no payment.

### Decision: Razorpay-shaped mock instead of Razorpay test mode now
- **Alternatives considered:** integrate Razorpay test mode immediately.
- **Why we chose this:** test mode needs an account, keys, and a public URL for webhooks (ngrok). The mock runs offline, can simulate timeouts and duplicate deliveries on demand (real test mode can't easily do that), and uses the real signature algorithms.
- **What we gave up:** the mock's order store is in memory (lost on restart), and we haven't yet proven the integration against Razorpay's real servers.
- **When we would revisit:** the moment you have Razorpay test keys. See section 10 for the steps.

### Decision: notifications written in the purchase transaction; Redis and email after commit (not a full Outbox)
- **Alternatives considered:** the Transactional Outbox pattern; sending inside the transaction.
- **Why we chose this:** an in-app row that's atomic with the purchase covers the must-not-lose part. The Outbox adds a table and a poller for the nice-to-have part (email).
- **What we gave up:** a crash between commit and send loses that email.

---

## 7. Interview questions

> Answer out loud first. Then read. Ordered easy → hard.

### Beginner

**Q: What does "idempotent" mean? Give an example from your project.**
A: Doing something twice has the same effect as doing it once. In SecureLeaf, starting a checkout is idempotent: the browser sends a unique key with the request, so if the user double-clicks or the request is retried, the server finds the order it already made for that key and returns it instead of creating a second one.

**Q: Why do you store money as integers in paise instead of decimals like 499.00?**
A: Floating-point numbers can't represent most decimal fractions exactly: 0.1 + 0.2 isn't 0.3. Over many calculations, money drifts by fractions of a paisa. Storing the smallest unit as a whole number means all arithmetic is exact. Payment gateways like Razorpay and Stripe take amounts in the smallest unit for the same reason.

**Q: What's the difference between an order, a payment, and an entitlement?**
A: The order is the intent: "this buyer wants this product at this price". The payment is the money movement for that order: status, gateway payment ID, the audit trail. The entitlement is the actual access grant: "this buyer may read this product". Keeping them separate means a failed payment doesn't touch access, a free product can have an order without a payment, and later a refund can revoke the entitlement without deleting any history.

**Q: What's a webhook?**
A: An HTTP request that another service sends to *your* server when something happens: the reverse of calling their API. Razorpay calls our `/api/webhooks/razorpay` when a payment is captured or fails. We use it because the buyer's browser might never report back.

**Q: What is a transaction, and why does buying need one?**
A: A group of database changes that all happen or none do. Completing a purchase updates the payment, the order, adds an entitlement, bumps the sales count and adds notifications. If any step fails, we don't want a half-state, like an order marked paid with no access granted, so they're all in one transaction.

**Q: Why can't the creator buy their own product?**
A: It would inflate their sales count and route money in a circle through the platform fee. The server checks it (`CANNOT_BUY_OWN_PRODUCT`), and the UI hides the Buy button, but the server check is the real one.

**Q: What is a state machine?**
A: A model where something is always in one of a fixed set of states and only certain moves between them are allowed. Our order goes PENDING → COMPLETED → REFUNDED, or PENDING → FAILED. Status can only change through a `transitionTo` method that rejects illegal moves, so a bug can't quietly move a completed order back to pending.

**Q: What does "append-only" mean for your payment_events table?**
A: Rows are only ever inserted, never updated or deleted, so it's a complete history of every payment state change. We enforce it with a database trigger that raises an error on any UPDATE or DELETE, so even direct SQL can't rewrite history.

### Intermediate

**Q: A user double-clicks Buy. Walk me through why they don't get two orders.**
A: Three layers. First, both clicks carry the same idempotency key, generated once when the page loaded, so the second request finds the first order by key and returns it. Second, even with different keys, say from another tab, we look for an existing PENDING order for that buyer and product and reuse it. Third, both of those are "check then act", so two simultaneous requests could both check before either inserts. To prevent that, the first thing `initiateOrder` does is lock the buyer's own row with `SELECT … FOR UPDATE`, so the second request waits until the first commits and then sees its order. I have a test that fires ten concurrent requests and asserts exactly one order exists.

**Q: What's a race condition? Show me one you fixed.**
A: A bug where the outcome depends on timing between concurrent operations. The one I care most about is payment completion: the browser's confirmation and Razorpay's webhook often arrive within milliseconds of each other. Both would check "is the order completed?", both see no, and both grant the entitlement. The fix is to lock the order row first. Whichever gets the lock completes the order, and the other waits, then sees COMPLETED and does nothing. I test it with eight threads completing the same order at once and assert exactly one entitlement, one COMPLETED event and a sales count of one.

**Q: Pessimistic vs optimistic locking: which did you use and why?**
A: Pessimistic, `SELECT … FOR UPDATE`, for checkout and payment completion. Optimistic locking lets both proceed and fails the loser at commit time, which is great when conflicts are rare and retrying is cheap. But here the loser is a buyer who has already paid, and failing their request would be terrible. Pessimistic locking makes them wait a few microseconds and then succeed correctly. Contention is per buyer or per order, so waits are tiny. Ironically, the product table *does* have optimistic locking via `@Version`, which is why I increment `total_sales` with a SQL update instead of through the entity.

**Q: Why increment total_sales with `UPDATE … SET total_sales = total_sales + 1` instead of in Java?**
A: In Java it's read-modify-write: two concurrent purchases both read 5 and both write 6, so a sale is lost. Worse, the Product entity has a `@Version` column, so the second write would throw an optimistic-lock exception and roll back a paid purchase. A single SQL statement is atomic, because Postgres does the read and write under its own row lock, and a JPQL bulk update bypasses the entity's version check.

**Q: How do you verify a webhook really came from Razorpay?**
A: HMAC-SHA256. Razorpay signs the raw request body with a webhook secret that only we and they know, and sends the signature in `X-Razorpay-Signature`. We recompute it over the exact raw bytes and compare in constant time. If it doesn't match we return 400 and touch nothing. The endpoint has no JWT because Razorpay can't log in; the signature *is* the authentication.

**Q: Why a hash with a secret (HMAC) instead of just SHA-256 of the body?**
A: Anyone can compute SHA-256 of a body, so it only proves the body wasn't corrupted, not who sent it. An attacker could send a fake body with its correct plain hash. HMAC needs the secret, so a valid HMAC proves the sender knew the secret.

**Q: Why must you compute the signature over the raw bytes?**
A: The signature is over the exact bytes Razorpay sent. If a framework parses the JSON into an object and I re-serialise it, spacing and key order can change, the HMAC no longer matches, and I'd reject genuine webhooks. So the controller takes the body as a `byte[]`, verifies first, and parses afterwards.

**Q: What's a timing attack, and how did you prevent one?**
A: `String.equals` returns as soon as it finds a mismatching character, so comparing takes slightly longer the more leading characters are correct. An attacker who can measure response times can guess a valid signature one character at a time. `MessageDigest.isEqual` always compares every byte, so timing reveals nothing.

**Q: Webhooks can be delivered more than once. How do you handle that?**
A: Every event has a unique ID that we store in `payment_events.provider_event_id`, which has a UNIQUE constraint. Inside the order lock we check whether we've seen that ID and skip it if so. And even without the ID, completing an already-COMPLETED order is a no-op. We still return 200 for duplicates, because a non-2xx response tells the provider to retry, and we want duplicates to stop, not multiply. In dev, the mock gateway sends every webhook twice on purpose, to prove this works.

**Q: Why do you check for a duplicate event *after* taking the lock, not before?**
A: If two copies of the same webhook arrive together and both check before locking, both see "not processed yet" and both proceed: the same check-then-act race. Checking after the lock means the second copy waits for the first to commit, then sees its event row and stops. The UNIQUE constraint is a final backstop either way.

**Q: What response code do you return for a webhook about an order you don't recognise?**
A: 200, with a warning logged. Non-2xx means "please retry", and retrying won't make an unknown order exist, so we'd just get the same webhook for 24 hours. We return 5xx only for our own transient problems, like the database being down, where a retry would actually succeed.

**Q: What does `@TransactionalEventListener(AFTER_COMMIT)` do, and why did you need it?**
A: It delays an event listener until the surrounding transaction has committed, and drops the event if it rolls back. We send "purchase confirmed" emails and Redis messages that way. If they were sent inside the transaction and it then rolled back, the buyer would get an email about a purchase that never happened, and you can't unsend an email.

**Q: Why is `ownedByMe` a `@BatchMapping` instead of a normal field resolver?**
A: A normal resolver runs once per product, so a page of 20 products would run 20 entitlement queries: the N+1 problem. `@BatchMapping` collects all products in the response and resolves them in one call with a single `WHERE product_id IN (…)` query. It must return results in the same order as the input list.

**Q: Why does another user's order ID return "not found" instead of "forbidden"?**
A: Forbidden confirms the order exists, which leaks information. By putting the buyer ID in the WHERE clause, someone else's order simply isn't found, so an attacker can't tell "doesn't exist" from "not yours". This is BOLA protection, OWASP API Security #1.

**Q: A buyer's product gets unpublished by the creator. What happens to their access?**
A: Nothing. The library query deliberately doesn't filter on product status or soft-delete. A sale is a promise; the entitlement outlives the listing. That's also why products are soft-deleted rather than hard-deleted.

### Advanced / follow-up probes

**Q: The payment gateway times out. What does your system do?**
A: This is the scenario webhooks exist for. A timeout means we *don't know*: the money might have been taken. So the checkout page never treats a timeout as failure. It tells the buyer not to pay again and polls the order's status. Meanwhile, if the charge went through, Razorpay's webhook arrives and completes the order through the same idempotent path. My mock's TIMEOUT button does exactly this: it captures the money, returns 504 to the browser, and sends the webhook four seconds later. The page flips to "confirmed" without the browser ever succeeding.

**Q: Webhooks can arrive out of order. Where does that bite you?**
A: A buyer's card is declined, they retry and succeed, but the `payment.failed` for the first attempt arrives *after* the `payment.captured`. If we applied it blindly we'd mark a completed payment as failed. So `recordFailure` ignores failures for orders that are no longer PENDING. The state machine would also reject COMPLETED → FAILED, so there are two independent guards.

**Q: How do you get "exactly-once" payment processing?**
A: You can't get exactly-once *delivery* over a network: either you might lose a message or you might send it twice. So you choose at-least-once delivery, which the provider retries until acknowledged, and make processing idempotent: dedup by event ID, and make completing an already-completed order a no-op. The combination is often called "effectively once".

**Q: You load an order, check who owns it, then lock it. What's wrong with that?**
A: I actually wrote that first and caught it in review. Hibernate keeps one object per database row in its persistence context. If I load the order normally and then run `SELECT … FOR UPDATE`, Hibernate takes the lock but hands me the *same* object with its *old* field values. If a webhook completed the order while I waited for the lock, I'd still see PENDING and fulfil twice. The fix is to make the locking query the *first* read, then do the ownership check on the locked, fresh row.

**Q: Your `initiateOrder` holds a row lock while calling the gateway. Is that a problem?**
A: It's the "don't do network I/O inside a database transaction" smell. With a real gateway that's a ~200 ms call while holding a lock and a connection. The blast radius is small, because only that buyer's concurrent checkouts wait, but under load it ties up connection-pool slots. The fix is to split it: create the order and commit, call the gateway outside any transaction, then store the gateway order ID in a second short transaction, with a cleanup job for orders where step two never happened.

**Q: How would you design a payment system that never double-charges?**
A: Layered. Client-generated idempotency keys on every mutating request, stored with their results. One order per purchase intent, with the gateway's order ID stored so the gateway itself refuses to capture it twice. Row locks on the order for every state change, with idempotent transitions (already-COMPLETED means no-op). An explicit state machine so illegal jumps throw. Signed webhooks as the source of truth, deduplicated by event ID, returning 2xx for duplicates. An append-only audit log of every transition. Database constraints as the last line of defence, like a unique partial index on active entitlements. A reconciliation job that periodically asks the gateway about orders stuck in PENDING. And tests that fire concurrent and duplicate requests, because these bugs never show up single-threaded.

**Q: What's the Transactional Outbox pattern, and would you use it here?**
A: You write the message you want to send, like an email or a Kafka event, into an "outbox" table *in the same transaction* as the business change. A separate process reads the outbox, sends each message, and marks it sent, retrying until it succeeds. It fixes the gap where the server crashes after commit but before sending. We currently send emails from an after-commit listener, so a crash in that window loses the email. The in-app notification row is safe because it's in the transaction. For purchase receipts in production, I'd move to an outbox.

**Q: Why a partial unique index, and why isn't V1's unique constraint enough?**
A: V1 had UNIQUE on (order_id, product_id), which stops one order granting a product twice, but two *different* orders could each grant it. The rule we actually want is "at most one ACTIVE entitlement per buyer and product", while still allowing a re-purchase after a refund revokes the first one. A partial index, `UNIQUE (buyer_id, product_id) WHERE status = 'ACTIVE'`, expresses exactly that. It's the database backstop behind all the application-level locks.

**Q: How do you compute a 10% fee without losing a paisa?**
A: Keep the rate in basis points (1000 = 10%) so everything is integer maths: `fee = (price × 1000 + 5000) / 10000`, which rounds half-up. Then `creatorEarnings = price − fee`, derived by subtraction, so the two always add back to the price exactly. A CHECK constraint enforces that. Both are stored on the order item at purchase time, so changing the commission later never rewrites past earnings.

**Q: Why does the creator earnings API use a `Long` scalar?**
A: GraphQL's built-in Int is 32-bit signed, which maxes out at about 2.1 billion. In paise, that's ₹2.14 crore, and a successful creator's lifetime earnings could pass it. I added the extended-scalars `Long` type for aggregated amounts.

**Q: `@Transactional` on a private helper didn't work. Why?**
A: Spring implements `@Transactional` with a proxy around the bean. Only calls coming *through* the proxy, meaning from another bean, get a transaction. A method calling another method on `this` bypasses the proxy, so the annotation is ignored. You either annotate the public entry point, which is what I did in `verifyCheckout`, move the method to another bean, or inject a self-reference.

**Q: What's the difference between `Propagation.REQUIRED` and `MANDATORY`, and where did you use MANDATORY?**
A: REQUIRED joins the current transaction or starts one. MANDATORY joins the current one or throws. I used MANDATORY on `FulfillmentService.fulfil` and on creating notifications, because they must be part of the purchase transaction. If a future caller forgets to wrap them, they'd otherwise silently commit on their own, and MANDATORY makes that fail loudly instead.

**Q: The mock gateway exists in your codebase. How do you make sure it can never run in production?**
A: The mock's beans are conditional on `payment.gateway.provider=mock`. Production config sets it to `razorpay`, and on startup a config check refuses to boot if the chosen provider has no implementation. So production can't start until the real gateway exists, and it can never accidentally fall back to a "mark anything as paid" endpoint. Failing at deploy time is the cheapest possible failure.

**Q: What's the null bubbling behaviour in GraphQL, and how did it surprise you?**
A: If a field declared non-null (`InitiateOrderPayload!`) produces an error, GraphQL can't return null for it, so the null propagates to the nearest nullable parent. For a top-level mutation that's the whole `data` object. My anonymous-user test asserted that `initiateOrder` was null, but the path didn't exist at all because `data` itself was null. The error is still reported in `errors`.

**Q: How would you swap the mock for real Razorpay?**
A: Backend: write a `RazorpayGateway implements PaymentGateway` that calls the Razorpay Java SDK's `orders.create` with amount, currency and receipt, and add `razorpay` to the allowed providers. Set the three secrets as environment variables: key ID, key secret and webhook secret. Frontend: load Razorpay's `checkout.js`, open it with the key and gateway order ID, and in its `handler` call the same `verifyPayment` mutation, since the response shape is identical. Configure the webhook URL in Razorpay's dashboard, using ngrok for local dev. The signature code, order service, completion service and all tests stay the same.

### "Tell me about a bug you fixed"

**Q: Tell me about a subtle bug from this phase.**
A: While reviewing my payment verification code, I noticed I loaded the order with a normal query to check who owned it, then locked it with `SELECT … FOR UPDATE` before completing it. It looked correct, but Hibernate's persistence context keeps one object per row, so the locking query returned the object I'd already loaded, with its old status. If Razorpay's webhook had completed the order while my request waited for the lock, I'd still have seen PENDING and granted the product twice. I fixed it by making the lock the first read and checking ownership on the locked row. The lesson: a lock only protects you if the data you check was read *under* the lock.

**Q: Tell me about a bug your tests caught.**
A: My first integration-test run failed every checkout with "type orderstatus does not exist". Our Postgres enums are named `order_status`, and Hibernate maps them with `@JdbcTypeCode(NAMED_ENUM)`. That works when an enum is a *bound parameter*, but when I wrote the enum as a literal inside a JPQL query, `o.status = OrderStatus.PENDING`, Hibernate rendered it as `'PENDING'::OrderStatus`, casting to a type named after the Java class. The existing Phase 3 code never hit it because it used derived query methods, where the status is a parameter. I changed the three queries to take the status as a parameter behind a small default method. Lesson: ORMs have seams where two correct features combine into a broken query, and only running against the real database catches them. H2 wouldn't have.

**Q: Tell me about a time the tests weren't really testing anything.**
A: When I went to run the integration suite, I found that none of the `*IT` classes had ever run. There were four independent reasons. The POM has no Failsafe plugin, so `mvn verify` in CI only runs `*Test` classes. `HttpGraphQlTester` needs `spring-webflux` on the test classpath, which was missing, so the GraphQL tests would have crashed on setup. The pinned Testcontainers version speaks a Docker API that Docker Engine 29 rejects. And once all that was fixed, each test class passed alone but the suite failed together. The base class used `@Container static`, which stops Postgres after each class, while Spring caches the application context, so the second class's connection pool pointed at a dead database. I switched to the singleton-container pattern, starting the container once per JVM, added webflux with test scope, and pinned a newer Docker API version for the run. Wiring Failsafe into CI and upgrading Testcontainers are flagged as follow-ups. The lesson: a green CI badge only means something if you've seen the tests fail when they should.

---

## 8. Gotchas and bugs we hit

| # | Symptom | Root cause | Fix | Lesson |
|---|---|---|---|---|
| 1 | *(caught in design)* A forged signature would make the frontend refresh its JWT and retry | Error classified `UNAUTHORIZED`, and `apolloClient`'s errorLink treats that as "token expired" | `INVALID_PAYMENT_SIGNATURE` → `BAD_REQUEST` (`ErrorCode.java`) | Error classifications are an API contract with the client; check what the client *does* with them |
| 2 | *(caught in review)* Possible double fulfilment when browser + webhook race | Loaded order, *then* locked it; Hibernate returned the cached object with stale status | Lock is the first read; ownership checked on the locked row (`PaymentCompletionService.java:71`) | Data you check must be read under the lock |
| 3 | `type "orderstatus" does not exist` on every checkout | Enum *literal* in JPQL + `NAMED_ENUM` → `'PENDING'::OrderStatus` (Java class name ≠ `order_status`) | Pass statuses as bound parameters (`OrderRepository.findOpenOrders`) | Parameters, not literals; test on the real DB |
| 4 | Test expected `initiateOrder` = null, but the path didn't exist | GraphQL null bubbling: a non-null field's error nulls the parent (`data`) | Assert `$.data` is null | Non-null in the schema changes the error shape |
| 5 | No integration test had ever run | No Failsafe in POM; missing `spring-webflux` for `WebTestClient`; Testcontainers 1.19.8 vs Docker 29 API | Added test-scoped webflux; ran with `DOCKER_API_VERSION=1.44` / `-Dapi.version=1.44`. **Failsafe + Testcontainers upgrade still to do** | See a test fail before trusting it passes |
| 5b | Each IT class passed alone; together, every test after the first class died with `Failed to obtain JDBC Connection` after 60 s | `@Testcontainers` + `@Container static` in the base class **stops the container after each class**, but Spring **caches the context**, so the next class's connection pool pointed at a dead database | Singleton container: `static { postgres.start(); }`, one DB per JVM, plus a global `TRUNCATE` of every table before each test, because classes now share one DB (`AbstractIntegrationTest.java`) | Container lifecycle must match context lifecycle; shared state needs a shared reset |
| 6 | Creator dashboard revenue would be wrong after any price change | Computed `totalSales × current price` on the client | Server-side sum of snapshotted `order_items` splits | Never recompute history from current settings |
| 7 | *(avoided)* `MultipleBagFetchException` | Fetching two `List` collections (order items + product tags) in one entity graph | Leave tags lazy for the single-order query | Hibernate can't fetch two bags at once |
| 8 | *(avoided)* Earnings could overflow GraphQL `Int` | `Int` is 32-bit: ₹2.14 crore in paise | `Long` scalar for aggregates | Check your API's integer width |
| 9 | *(known)* Stored webhook `raw_payload` can't be re-verified later | `jsonb` normalises the JSON (key order, whitespace) | Accepted; store as `text` if re-verification is ever needed | `jsonb` ≠ the bytes you received |

---

## 9. New vocabulary

| Term | One-line meaning |
|---|---|
| Idempotency key | Client-generated unique ID that lets the server recognise and replay a retried request |
| Check-then-act race | Two concurrent requests both check a condition before either acts on it |
| Pessimistic lock | `SELECT … FOR UPDATE`: others wait until you commit |
| Optimistic lock | `@Version`: others proceed; the loser's write fails at commit |
| Lost update | Two read-modify-writes where the second overwrites the first |
| State machine | Fixed states + allowed transitions; everything else rejected |
| Append-only log | A table that only receives inserts, a full history |
| Webhook | A provider calling *your* server when something happens |
| At-least-once delivery | Messages retried until acknowledged, so duplicates are possible |
| Effectively-once | At-least-once delivery + idempotent processing |
| HMAC | Keyed hash that proves integrity *and* origin |
| Timing attack | Inferring secrets from how long comparisons take |
| Constant-time compare | Comparison whose duration doesn't depend on where inputs differ |
| Basis point (bps) | 0.01%; 10% = 1000 bps |
| Partial unique index | Uniqueness enforced only over rows matching a `WHERE` |
| Transactional Outbox | Write outgoing messages in the business transaction; send them from a poller |
| AFTER_COMMIT listener | Event handler that runs only if the transaction commits |
| Propagation MANDATORY | "Must run inside an existing transaction, or throw" |
| Self-invocation trap | Calling a `@Transactional` method on `this` bypasses Spring's proxy |
| Bulkhead | Separate resource pools so one workload can't starve another |
| DataLoader / `@BatchMapping` | Resolve a field for many objects in one batched call |
| GraphQL null bubbling | An error in a non-null field nulls its nearest nullable parent |
| Fail fast | Refuse to start or proceed on invalid configuration instead of degrading silently |
| Reconciliation job | Periodic sweep that compares your records with the provider's and fixes drift |

---

## 10. If I had to defend this in a code review

**Strongest points**
- **Every failure mode has a test.** Concurrent initiates, concurrent captures, duplicate webhooks, out-of-order webhooks, forged signatures, amount mismatch, BOLA, rollback-publishes-nothing, and DB-level invariants tested with raw SQL.
- **Defence in depth on the one rule that matters most**, "never grant twice": UI state, service check, idempotency key, row locks, and a partial unique index.
- **The provider boundary is clean.** Real Razorpay is one class plus config; the signature code is already the real algorithm.
- **History is trustworthy.** Status changes only go through `transitionTo` and are always paired with an append-only event, which the database itself protects.

**Weakest points, and what I'd fix first**
1. **No reconciliation for stale PENDING orders.** If a webhook is permanently lost, for example our endpoint was down beyond Razorpay's retry window, the order sits PENDING forever even though the buyer paid. **Fix first:** a `@Scheduled` job that finds PENDING orders older than ~15 minutes, asks the gateway for the order's payment status (`GET /orders/{id}/payments`), and feeds any capture through the same `recordCapture` path. That's standard practice at every payment company.
2. **Emails can be lost on a crash** between commit and send. Fix: Transactional Outbox.
3. **A gateway call inside `initiateOrder`'s transaction** (holding the buyer's row lock). Fine for the mock, a smell for real Razorpay. Fix: split into three steps as described in section 6.
4. **The integration suite isn't wired into CI** (no Failsafe), and Testcontainers 1.19.8 doesn't work with Docker Engine 29 out of the box. Fix: add `maven-failsafe-plugin` and upgrade Testcontainers. Getting the suite to run for the first time also exposed **three pre-existing Phase 2/3 test failures** (not Phase 4 code): `PreviewControllerIT.previewBytes_differ…` (its 10×10 fixture is smaller than the watermark's tiling step, so no text lands on the canvas), `ProcessingPipelineIT.processAsync_success` (asserts COMPLETED without waiting for the async job), and `MarketplaceQueryIT.search_usesTheFtsIndex` (`entityManager.flush()` outside a transaction). Final run: **56 ITs, 53 pass: all 32 Phase 4 tests plus 21 earlier ones.**
5. **No refunds yet.** The state machine allows COMPLETED → REFUNDED and the entitlement can be revoked, but there's no flow for it.

**Moving to real Razorpay, as a checklist**
1. Create a Razorpay account and generate test-mode keys (Settings → API Keys).
2. Add `com.razorpay:razorpay-java`; write `RazorpayGateway implements PaymentGateway` (`orders.create` with `amount`, `currency`, `receipt`); register it for `provider=razorpay`; add `"razorpay"` to `CommerceConfig.IMPLEMENTED_PROVIDERS`.
3. Set `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET` and `RAZORPAY_WEBHOOK_SECRET` as environment variables. Never commit them.
4. Frontend: replace `lib/mockGateway.ts` with `checkout.js` (`new Razorpay({ key, order_id, handler })`). The handler's response goes to the same `verifyPayment`.
5. In the Razorpay dashboard, add a webhook for `payment.captured` and `payment.failed` pointing at `/api/webhooks/razorpay` (use ngrok locally).
