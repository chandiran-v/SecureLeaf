# Phase 06 — Library, creator dashboard & live notifications

> **Status:** Done
> **Built:** 2026-09-25
> **Requirement IDs covered:** LIB-01..03, DASH-01..04, UPLOAD-09, UPLOAD-10, NOTIF-01..04 (`docs/requirements.md`)
> **Design doc:** [`docs/phases/phase-06-library-dashboard-notifications.md`](../phases/phase-06-library-dashboard-notifications.md) — decisions D1–D9
> **Commits:** `auto/issue-6` — see `git log` on that branch

---

## How to study this note

Read §1–2 for the shape of the problem, then §3 slowly — it's the part an interviewer will actually probe. §6 records the trade-offs and the honest limits. §10 names this phase's weakest point on purpose.

---

## 1. What we built, in plain English

Three earlier phases each built one piece of the buyer/creator loop but stopped short of finishing it:

- **The library** (Phase 4) listed only entitlements that were still ACTIVE, so a revoked purchase simply vanished instead of explaining what happened to it.
- **The creator dashboard** (Phase 2/4) showed a product's status and its all-time sales counter, but never what a specific product had *earned*, never what stage a PROCESSING upload was stuck at, and never a way to recover a FAILED upload short of re-uploading the whole file.
- **Notifications** (Phase 4) were written to the database and even published to Redis, but nothing was listening on the other end — the bell polled `myNotifications` every 30 seconds and called it done.

This phase closes all three gaps and adds the piece that makes "closes the loop" literal: **when the pipeline finishes processing a creator's upload, the creator finds out in real time**, not by refreshing a page.

**Before this phase:** a revoked entitlement disappeared from the library with no explanation; a FAILED upload was a dead end; a creator only learned their book went live by noticing the status badge had changed on a later visit; the bell could be up to 30 seconds late and blind to work happening in the background.

**After this phase:** the library shows every entitlement a buyer has ever held, with the reason "Read" is disabled when it is; the dashboard shows exactly which pipeline stage a PROCESSING product is on and exactly why a FAILED one failed, with one-click **Retry**; an UNPUBLISHED product can be **Republished**; and a `PROCESSING_COMPLETE`/`PROCESSING_FAILED` notification reaches the creator's open browser tab within seconds of the pipeline finishing — pushed, not polled.

---

## 2. Why it matters

- **A revoked purchase that just disappears looks like a bug**, not a business decision. Buyers who lose access (chargebacks, ToS violations) deserve to see *that* it changed, not silence.
- **"Upload failed" with no reason and no retry is a support ticket.** UPLOAD-10 exists specifically so a creator whose PDF was rejected for a fixable reason (encrypted, corrupt) can fix it and try again without re-doing the whole upload form.
- **Per-product revenue is the number creators actually care about.** "3 total sales" doesn't tell a creator whether their $50 book or their free lead-magnet is paying the bills — DASH-02 does.
- **Polling for notifications doesn't scale in the way that matters: it scales in latency, not in load.** At 10 users it's an unnoticeable 30-second delay; the *architectural* problem is that "poll every 30 seconds" is a client-side guess with no relationship to when something actually happened. Pushing removes the guess.
- **What would break if we skipped the hard parts:** no fresh-per-request stats query → an N+1 query for every product on every dashboard load, the exact bug Phase 3's marketplace listing was built to avoid. No single-use SSE ticket → a JWT sitting in a URL, in browser history and every proxy's access log, for as long as the token is valid. No re-read of the notification row before pushing it → a stale or wrong payload reaching the browser, silently drifting from what `myNotifications` would say.

---

## 3. New concepts introduced

### 3.1 SSE vs. WebSocket vs. polling — and why this phase picked SSE

**What it is:** three different ways a server can get information to a browser without the browser asking first (or, for polling, asking constantly).

**The analogy:** polling is calling a friend every 30 seconds to ask "anything new?". A WebSocket is opening a phone line that stays connected and either side can talk any time. Server-Sent Events (SSE) is more like a radio station: you tune in once (`GET`, over plain HTTP), and the station keeps talking to you — but you can't talk back on that same channel.

**Why we needed it here:** every message this phase pushes flows in exactly one direction, server → browser. The browser never needs to send anything back over the same channel (marking a notification read is a completely separate GraphQL mutation). That one fact makes SSE strictly enough:

| | WebSocket | SSE |
|---|---|---|
| Direction | Bidirectional | Server → client only |
| Transport | Its own protocol (upgrade from HTTP) | Plain HTTP, `text/event-stream` |
| Reconnect | You write it yourself | Built into `EventSource` |
| Server support here | Needs `spring-websocket` + a broker/session registry | `SseEmitter` — already in `spring-boot-starter-web` |
| Proxies/load balancers | Need explicit WebSocket support | Just HTTP — works everywhere HTTP does |

**How it works:** the browser's `EventSource` opens a normal `GET` request; the server never closes the response and keeps writing `event: ...\ndata: ...\n\n` frames to it. `EventSource` parses those frames itself and reconnects automatically (with a browser-managed backoff) if the connection drops — we didn't have to write any of that reconnect logic ourselves for the *transport* layer (we do add a ticket-refresh layer on top, see §3.2).

**In our code:** [`NotificationStreamController.java:37-44`](../../backend/src/main/java/com/secureleaf/notification/controller/NotificationStreamController.java#L37)
```java
@GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter stream(@RequestParam(required = false) String ticket) {
    Long userId = ticketService.consumeTicket(ticket);
    if (userId == null) {
        throw new BusinessException(ErrorCode.SSE_TICKET_INVALID, "Missing, unknown, or already-used ticket.");
    }
    return emitterRegistry.register(userId);
}
```

**What breaks without it:** picking WebSocket for a one-way feed means writing (and testing, and securing) a whole bidirectional session-management layer for a use case that never uses the "bidirectional" half — pure accidental complexity. Picking polling means every user's browser hits the server on a fixed clock regardless of whether anything happened, and the *fastest* anyone ever finds out about a change is however long they're willing to wait between polls.

---

### 3.2 One-time tickets for authenticating a stream `EventSource` can't put a header on

**What it is:** every other authenticated call in this app sends `Authorization: Bearer <jwt>`. `EventSource` has no API for custom request headers — it can only `GET` a URL. So the JWT itself can't be the credential for this one endpoint.

**The analogy:** a JWT is your ID card — you don't want to leave a photocopy of it lying in a public log book (browser history, proxy access logs, `Referer` headers) just because one door's reader can't scan an ID card, only a barcode. A ticket is a fresh, disposable barcode: valid for one entry, expires in half a minute, and reveals nothing about you beyond "let this specific person in once."

**Why we needed it here:** putting the JWT in the URL (`?token=<jwt>`) would work — mechanically — but a JWT is valid for 15 minutes (`jwt.access-token-expiry-ms`) and grants access to *everything* the user can do. A URL is exactly the kind of string that ends up in server logs, browser history, and (if anyone ever put this behind a corporate proxy) a shared log file. A ticket that's dead the instant it's used, and dead anyway 30 seconds later, shrinks that exposure to almost nothing.

**How it works:**
1. `notificationStreamTicket` (authenticated, normal JWT) mints a random 256-bit ticket and stores it in Redis: `SET sse-ticket:{ticket} {userId} EX 30`.
2. The stream endpoint reads it with **`GETDEL`** — read and delete in one atomic step — so two requests racing to use the same ticket can never both win; the second one always finds it already gone.

**In our code:** [`NotificationStreamTicketService.java:36-51`](../../backend/src/main/java/com/secureleaf/notification/service/NotificationStreamTicketService.java#L36)
```java
public String issueTicket(Long userId) {
    String ticket = randomTicket();
    redisTemplate.opsForValue().set(TICKET_KEY_PREFIX + ticket, String.valueOf(userId), TICKET_TTL);
    return ticket;
}

public Long consumeTicket(String ticket) {
    if (ticket == null || ticket.isBlank()) return null;
    String userId = redisTemplate.opsForValue().getAndDelete(TICKET_KEY_PREFIX + ticket);
    return userId == null ? null : Long.valueOf(userId);
}
```
This is the same *pattern* Phase 5 already used for the DRM tile signature's single-use guarantee (`SET NX` there, `GETDEL` here) — same idea, different Redis primitive because the two problems are shaped slightly differently: a tile signature is self-verifying (HMAC) and just needs a "was this used before" flag; a ticket needs to *carry* a value (which user) that the reader doesn't already know.

**What breaks without it:** without single-use, a ticket URL that leaked (a saved screenshot, a shared clipboard, a badly configured logging proxy) would keep working for its whole 30-second window, and — worse — could be *replayed* over and over by anyone who captured it. `GETDEL` closes both holes with one Redis primitive.

---

### 3.3 Redis Pub/Sub fan-out across instances — and its at-most-once caveat

**What it is:** every backend instance runs one `RedisMessageListenerContainer` subscribed to `notifications:user:*`. When any instance publishes to `notifications:user:42`, *every* subscribed instance's listener fires — including the one that published it.

**The analogy:** Pub/Sub is a radio broadcast, not a mailbox. If your radio is off (no one subscribed to that frequency, or no one *currently* listening for that exact station), the broadcast happened and is gone forever — nothing was recorded for you to catch up on later. A mailbox (a database row) still has your letter whenever you check it.

**Why we needed it here:** a browser's `EventSource` connects to exactly one backend instance — whichever the load balancer picked. If the notification-creating request lands on instance A but the creator's open tab is connected to instance B, A has no direct way to reach into B's in-memory emitter map. Redis Pub/Sub is the message bus that lets every instance hear about it, so whichever instance is actually holding that user's connection can act on it.

**How it works:**
1. `RedisNotificationPublisher` (already built in Phase 4) publishes the new notification's `NotificationCreatedEvent` JSON to `notifications:user:{recipientId}`.
2. `NotificationRedisSubscriber`, registered on every instance via `SseConfig`'s `RedisMessageListenerContainer`, receives it, **re-reads the actual row from Postgres** (not the Redis payload verbatim — see the "why" below), and hands the resulting `NotificationDto` to `SseEmitterRegistry`.
3. `SseEmitterRegistry` looks up its own **local, in-memory** `Map<Long, Set<SseEmitter>>` for that user id. If this instance has no open connection for that user, it does nothing — cheaply and correctly, because *some other instance* is the one that will.

**In our code:** [`NotificationRedisSubscriber.java:37-52`](../../backend/src/main/java/com/secureleaf/notification/service/NotificationRedisSubscriber.java#L37)
```java
public void onMessage(Message message, byte[] pattern) {
    NotificationCreatedEvent event = objectMapper.readValue(message.getBody(), NotificationCreatedEvent.class);
    notificationService.findDtoById(event.notificationId()).ifPresent(dto ->
            emitterRegistry.sendToUser(event.recipientId(), EVENT_NAME, objectMapper.writeValueAsString(dto)));
}
```
Re-reading by id (rather than trusting the Redis message's own fields) means the pushed payload is *exactly* what `myNotifications` would return for that row — one mapping, one source of truth, impossible for the two to drift apart. It also means the pushed object never carries `recipientEmail`, which `NotificationCreatedEvent` does (the email dispatcher needs it) but a browser-visible stream should not.

**The at-most-once caveat — the actual interview question here:** Pub/Sub delivers to whoever is subscribed *right now*, once, and keeps nothing. If a creator's browser tab is closed when their book goes LIVE, that push is simply never delivered — there's no queue holding it for later. **This is fine, on purpose**, because Pub/Sub is only the "something changed, go look" signal, never the record of truth. The `notifications` table is the source of truth (it's written *before* anything is published — see D9 of Phase 4's design doc), and the bell's initial `MY_NOTIFICATIONS` query — plus its polling fallback (§3.6) — is exactly the mechanism that lets a reconnecting client catch up on whatever Pub/Sub silently dropped. If SSE dropped a message, the row is still there the next time anything asks.

**What breaks without this framing:** treating Redis Pub/Sub as a reliable message queue (assuming "publish" means "will eventually be delivered") would be a genuine production bug the day someone's laptop is asleep when their sale notification fires. The fix isn't "make Pub/Sub reliable" (wrong tool) — it's "never let anything depend on Pub/Sub for correctness," which is exactly how this phase built it.

---

### 3.4 A named `DataLoader` shared across two GraphQL fields (D2)

**What it is:** GraphQL's `@BatchMapping` (used already in Phase 4 for `Product.ownedByMe`) registers one `DataLoader` per field — `"Product.ownedByMe"` — so that field's resolver runs once for the *whole* result list instead of once per item (the classic N+1 fix). `Product.salesCount` and `Product.netEarningsPaise` are two *different* schema fields that both need the exact same `GROUP BY product_id` aggregate query. Naively giving each its own `@BatchMapping` would still be "no N+1 per field" — but it would run the identical query **twice** whenever a client selects both fields, which the dashboard always does.

**The analogy:** two cashiers at a shop who both need the day's total sales figure. If each of them separately re-runs the same report from scratch, that's wasted work even though neither one is doing anything wrong on their own — the fix is one shared report both of them read from.

**Why we needed it here:** the acceptance criterion is explicit: "resolved in one aggregate query." A `DataLoader` registered under an explicit **name** (rather than tied to one field) lets two independent resolver methods share it — every `.load(key)` call made against that same named loader during one GraphQL request gets folded into a single batch, regardless of which field triggered it.

**How it works:**
1. `ProductStatsLoaderConfig` registers one loader named `"productStats"` at startup, backed by `OrderItemRepository.sumStatsByProductIds` — one `GROUP BY product_id` query.
2. `ProductStatsResolver` has two `@SchemaMapping` methods (not `@BatchMapping` — each runs per-product, not per-list), one for `salesCount` and one for `netEarningsPaise`. Both pull the **same** loader out of `DataFetchingEnvironment.getDataLoaderRegistry()` and call `.load(productId)` on it.
3. graphql-java's DataLoader dispatch mechanism collects every `.load()` call made in that "tick" — from *both* fields, across *every* product in the response — and fires the registered batch function exactly once.

**In our code:** [`ProductStatsLoaderConfig.java:37-47`](../../backend/src/main/java/com/secureleaf/commerce/ProductStatsLoaderConfig.java#L37)
```java
public static final String LOADER_NAME = "productStats";
...
batchLoaderRegistry.<Long, ProductStatsDto>forName(LOADER_NAME)
        .registerMappedBatchLoader((productIds, env) -> Mono.fromCallable(() ->
                orderItemRepository.sumStatsByProductIds(productIds).stream()
                        .collect(Collectors.toMap(ProductStatsDto::productId, dto -> dto))));
```
and both fields drawing from it: [`ProductStatsResolver.java:33-47`](../../backend/src/main/java/com/secureleaf/commerce/resolver/ProductStatsResolver.java#L33).

Ownership is checked *before* the loader is even asked: a caller who isn't the product's own creator gets a completed future of `null` without ever touching the query (`loadOwnStats`, same file). Products with genuinely zero completed sales get real zeros for the owner, not `null` — `null` means "not your product," not "no sales yet."

**What breaks without it:** two separate `@BatchMapping` methods, each correct in isolation, would double the query count on every dashboard load a client actually uses (both fields, together) — a subtler, field-level version of the exact N+1 problem Phase 3's marketplace listing was built to eliminate. `ProductStatsIT.bothFields_shareOneAggregateQuery_perRequest` asserts this with a Mockito spy on the repository method, verifying it runs exactly once — see the Gotchas table (§8) for why that test isn't a raw Hibernate-statement-count assertion.

---

### 3.5 State transitions as guarded operations (`retryProcessing`, `republishProduct`)

**What it is:** `retryProcessing` and `republishProduct` are each only legal from exactly one starting `ProductStatus` (FAILED and UNPUBLISHED respectively). Calling either from any other status is refused loudly, not silently ignored.

**The analogy:** the same idea `Order`/`Payment` already use (Phase 4's `OrderStatus.canTransitionTo`) — a state machine's edges are the whole point. A door that's already open doesn't need an "open" button that does nothing; it needs one that says "this door is already open."

**Why we needed it here:** without an explicit check, calling `retryProcessing` on a LIVE product would happily reset an unrelated job or throw a confusing `NullPointerException` deep in a repository call. An explicit guard turns that into one clear, typed error (`INVALID_STATE_TRANSITION`) at the top of the method, before anything is mutated.

**How it works:** [`ProductRecoveryService.java:49-82`](../../backend/src/main/java/com/secureleaf/creator/service/ProductRecoveryService.java#L49)
```java
if (product.getStatus() != ProductStatus.FAILED) {
    throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
            "Product " + productId + " is not FAILED (currently " + product.getStatus() + ") — nothing to retry.");
}
```
`retryProcessing` finds the most recent `ProcessingJob` for the product (highest id — a re-upload always inserts a fresh job row rather than reusing an old one, so "most recent" always means "the attempt that matters right now"), resets its `retryCount` to 0 and status to `QUEUED`, and flips the product back to `PROCESSING`. It does **not** call into the pipeline directly — `ProcessingJobWorker`'s existing 5-second `@Scheduled` poll picks up the freshly-QUEUED row exactly the way it picks up a brand-new upload. One recovery path, reusing all of the pipeline's existing retry/failure machinery, rather than a second bespoke "kick the pipeline" code path.

`republishProduct` adds one more guard beyond the status check: it also confirms a `DocumentVersion` with `processedAt != null` actually exists — an UNPUBLISHED product that was *never* successfully processed (impossible in the current UI flow, but not impossible to reach by calling the mutation directly) can't be republished into a broken LIVE state.

Both go through the same two-layer BOLA guard every mutating operation in this codebase uses: `@PreAuthorize("hasRole('CREATOR')")` at the resolver (*is* this user a creator), then `assertOwnership` inside the service (*does this creator own this specific product*) — see `ProductService`'s javadoc from Phase 2 for the original explanation of why both checks are needed.

**What breaks without it:** silently no-op'ing an illegal transition hides a real bug from the person who triggered it (did my retry actually do anything?); silently *allowing* it (e.g., "retrying" a LIVE product) could reset a perfectly good product back into PROCESSING for no reason. An explicit, typed rejection is strictly better than either.

---

### 3.6 Soft delete preserving buyer rights (reconfirmed, extended to the library and the viewer)

**What it is:** `deleteProduct`/`unpublishProduct` (both from Phase 2) only ever set `deleted_at`/flip `status`; they never touch `entitlements`. This phase's job was to make sure that promise actually holds all the way through the buyer-facing surfaces this phase touches.

**Why it needed re-checking here:** `EntitlementService.myLibrary` used to filter to `status = ACTIVE` only (Phase 4). That's a *different* filter from "product not deleted" — but the two are easy to conflate, and D1 explicitly re-widens the query to **every** entitlement status, independent of what state the underlying product is in.

**How it works:** [`EntitlementService.java:33`](../../backend/src/main/java/com/secureleaf/commerce/service/EntitlementService.java#L33)
```java
public List<EntitlementDto> myLibrary(Long buyerId) {
    return entitlementRepository.findByBuyerIdOrderByGrantedAtDesc(buyerId)
            .stream().map(CommerceMapper::toEntitlementDto).toList();
}
```
[`EntitlementRepository.java:41`](../../backend/src/main/java/com/secureleaf/commerce/repository/EntitlementRepository.java#L41) drops the old `AND status = :status` filter entirely — no product-status filter, no `deleted_at` filter, because none of that has ever been relevant to *this* query; a buyer's access is a fact about the `entitlements` row, never about the product's current listing state. `ViewerSessionService.startViewerSession` (Phase 5) already only checks `EntitlementStatus.ACTIVE`, with no join to the product's own status — so a deleted/unpublished product was already viewable by an existing owner; `LibraryIT.deletedProduct_staysInTheBuyersLibrary_andRemainsViewable` is what proves it, end to end, rather than trusting that two independently-correct pieces compose correctly.

D5 also asked for a UI-level safety net: both **Unpublish** and **Delete** now go through a confirm dialog (`ConfirmDialog.tsx`) that says outright — "Buyers who already own it keep their access and can still read it" — so a creator never has to *wonder* whether clicking Delete will hurt the people who already paid.

**What breaks without it:** the moment a "clean up my catalog" delete also silently revoked existing buyers, this product's core promise ("buy once, keep access forever, unless we explicitly revoke it") would be false — and worse, false in a way a creator could trigger by accident with one click on the wrong button.

---

## 4. Best practices applied

| Practice | What we did | Why it matters | Where |
|---|---|---|---|
| Named, shared `DataLoader` | One loader backs two GraphQL fields | One aggregate query regardless of how many of the two fields a client selects | `ProductStatsLoaderConfig.java:37` |
| Object-level authorization (BOLA) | `assertOwnership` before every mutation | Role check ("is a creator") is not the same as ownership check ("owns *this* product") | `ProductRecoveryService.java:78` |
| Guarded state transitions | Explicit status check + typed `INVALID_STATE_TRANSITION` | Illegal moves fail loudly, at the top, before any mutation | `ProductRecoveryService.java:53` |
| Single-use, short-lived credentials | Redis `GETDEL` ticket, 30s TTL | Shrinks the blast radius of a leaked URL to near zero | `NotificationStreamTicketService.java:49` |
| Re-read from source of truth before push | SSE listener re-fetches the `Notification` row by id | The pushed payload can never drift from what a fresh query would return | `NotificationRedisSubscriber.java:46` |
| AFTER_COMMIT side effects | Pipeline notifications reuse `NotificationService`'s existing async, post-commit dispatch | No notification for a pipeline run whose DB writes ultimately rolled back | `DocumentProcessingService.java:246,280` |
| Graceful degradation | SSE → polling fallback after 3 failed reconnects | A flaky network or blocked SSE proxy still gets *a* working bell | `useNotificationStream.ts:6` |
| Soft delete + explicit re-verification | Re-widened `myLibrary` query, dedicated IT proving delete/unpublish don't touch access | Trusting that two independently-correct pieces compose correctly is how these bugs slip through | `LibraryIT.java` |

---

## 5. What does what — file map

| File | Responsibility |
|---|---|
| `NotificationStreamTicketService.java` | Issues/consumes the one-time SSE ticket (Redis `SET`/`GETDEL`) |
| `NotificationStreamController.java` | `GET /api/notifications/stream` — REST, ticket-authenticated, returns an `SseEmitter` |
| `SseEmitterRegistry.java` | This instance's local `userId → open emitters` map; sends, heartbeats, cleans up |
| `NotificationRedisSubscriber.java` | Redis Pub/Sub listener; re-reads the row, forwards to `SseEmitterRegistry` |
| `SseConfig.java` | Wires the `RedisMessageListenerContainer` to `notifications:user:*` |
| `ProductStatsLoaderConfig.java` | Registers the shared `"productStats"` named `DataLoader` |
| `ProductStatsResolver.java` | `Product.salesCount`/`netEarningsPaise` — ownership check + shared loader |
| `ProductProcessingStatusResolver.java` | `Product.processingStage`/`failureReason` — batched, per-product-list |
| `ProductRecoveryService.java` | `retryProcessing`/`republishProduct` — guarded state transitions |
| `ProductRecoveryResolver.java` | GraphQL mutations for the above |
| `NotificationService.java` | `+notifyProcessingComplete/Failed`, `+findDtoById`, `+markAllRead` |
| `NotificationResolver.java` | `+notificationStreamTicket` query, `+markAllNotificationsRead` mutation |
| `useNotificationStream.ts` | Frontend: ticket fetch, `EventSource`, cache prepend, reconnect/fallback |
| `NotificationBell.tsx` | Wires the stream hook to the badge/dropdown/toast, "Mark all read" |
| `LibraryPage.tsx` | All entitlement statuses, disabled Read + reason, client-side search |
| `CreatorDashboardPage.tsx` | Stage/failure display, Retry/Republish, confirm-before-mutate |
| `ConfirmDialog.tsx` | Small reusable confirm/cancel modal |

**Request trace — a creator's product finishes processing, live, end to end:**
1. `ProcessingJobWorker`'s scheduled poll hands the job to `DocumentProcessingService.processAsync` →
2. the pipeline finishes, `markLive` flips the product LIVE and calls `notificationService.notifyProcessingComplete(product)` (same transaction) →
3. on commit, `NotificationDispatcher` (Phase 4) runs `RedisNotificationPublisher.publish` on the async notification executor →
4. every instance's `NotificationRedisSubscriber.onMessage` fires; the instance holding the creator's open tab re-reads the row and calls `SseEmitterRegistry.sendToUser` →
5. the creator's `EventSource` receives a `notification` event; `useNotificationStream` prepends it into the Apollo cache →
6. `NotificationBell` re-renders with the new unread count and a toast, with no polling involved anywhere in this chain.

---

## 6. Design decisions and trade-offs

### Decision: SSE, not WebSocket, for real-time delivery
- **Alternatives considered:** WebSocket + STOMP (Spring's other real-time option); keep polling but shorten the interval.
- **Why we chose this:** the data only ever flows one way; `SseEmitter` needs no new dependency; browsers reconnect automatically. See §3.1.
- **What we gave up:** no path for the browser to push data back over the same channel — not needed today, but would require a different mechanism (a GraphQL mutation, which already exists) if it ever were.
- **When we would revisit:** if a future feature needed true bidirectional low-latency messaging (live chat, collaborative editing) — SSE would be the wrong tool for that, but nothing here needs it.

### Decision: one-time Redis ticket, not the JWT, to authenticate the stream
- **Alternatives considered:** put the JWT in the query string; use a cookie instead of a bearer token for this one endpoint.
- **Why we chose this:** shrinks a leak's blast radius from "the whole 15-minute access token, replayable" to "one already-dead 30-second ticket." See §3.2.
- **What we gave up:** one extra network round trip (fetch a ticket, then connect) per (re)connect attempt.
- **When we would revisit:** never, for this use case — the extra round trip is cheap and the security property is worth it.

### Decision: a shared named `DataLoader`, not two independent `@BatchMapping` fields
- **Alternatives considered:** two separate `@BatchMapping` methods (the Phase 4 `ownedByMe` pattern); a request-scoped Spring bean caching the result.
- **Why we chose this:** genuinely one query regardless of which/how-many of the two fields a client asks for, using a mechanism Spring for GraphQL already ships (no new caching layer to reason about). A request-scoped `@RequestScope` bean was considered and rejected — graphql-java's async execution strategy can, in principle, resolve independent fields on different threads, and a `@RequestScope` bean's `ThreadLocal`-backed lookup isn't guaranteed safe across that; the `DataLoaderRegistry` Spring for GraphQL hands to every resolver is explicitly designed to be shared safely instead.
- **What we gave up:** slightly more code than a single `@BatchMapping` method — a `@Configuration` class to register the loader, plus two `@SchemaMapping` methods instead of one `@BatchMapping`.
- **When we would revisit:** if a third field ever needed the same aggregate, it plugs into the same named loader with no new query.

### Decision: `retryProcessing` re-queues into the existing poller, not a direct pipeline call
- **Alternatives considered:** call `DocumentProcessingService.processAsync` directly from the mutation.
- **Why we chose this:** one code path handles "a job is ready to run," whether it got there via a fresh upload or a retry — no second, parallel way for a job to start that could drift out of sync with the first (e.g. forgetting to reset `workerId`/`claimedAt`).
- **What we gave up:** a retry waits up to 5 seconds (the poll interval) before actually starting, instead of starting immediately.
- **When we would revisit:** if retry latency ever became a user-visible complaint — the fix would be to shorten the poll interval or add an explicit wake-up signal, not to add a second pipeline entry point.

---

## 7. Interview questions

### Beginner
**Q: Why did you pick Server-Sent Events instead of WebSockets for notifications?**
A: Because the data only ever flows one way, server to browser. WebSocket gives you a full bidirectional channel, which is more than this needed — SSE is plain HTTP, the browser's `EventSource` already knows how to reconnect, and Spring's `SseEmitter` was already on the classpath. Simpler tool, same job.

**Q: What happens if a buyer's tab is closed when a notification is created?**
A: They just don't get the push — Redis Pub/Sub doesn't queue anything for offline subscribers. But the notification row is already in Postgres before anything is published, so the next time their bell loads `myNotifications`, it's right there. The push is a "hey, go look" signal, not the delivery mechanism itself.

### Intermediate
**Q: Why not just put the JWT in the SSE URL instead of building a whole ticket system?**
A: `EventSource` can't set headers, so the URL is the only thing you control. A JWT is valid for 15 minutes and can do anything the user can do; putting it in a URL means it can end up in browser history, a proxy's access log, anywhere URLs get logged — for the full 15 minutes. A ticket is scoped to nothing but "which user is this," lasts 30 seconds, and is deleted from Redis the instant it's used, so even if one leaked, the window to abuse it is close to zero.

**Q: Walk me through why `Product.salesCount` and `Product.netEarningsPaise` don't cause two separate database queries.**
A: Normally in Spring for GraphQL, `@BatchMapping` gives each field its own `DataLoader`, so two fields mean two batched queries even though neither one is N+1 on its own. Here I registered one `DataLoader` under an explicit name instead of letting the framework auto-name one per field, and both fields' resolvers pull that *same* loader out of the `DataFetchingEnvironment` and call `.load()` on it. graphql-java collects every `.load()` call made during that tick — from both fields, across every product — into one batch dispatch, so the underlying `GROUP BY product_id` query runs exactly once no matter which or how many of the two fields got selected.

### Advanced / follow-up probes
**Q: Redis Pub/Sub gives you at-most-once delivery. Why is that acceptable here, when it wouldn't be for, say, payment events?**
A: Because Pub/Sub was never the record of truth for anything in this system — it's purely a "wake up and look" signal layered on top of a database row that's already durable. Payment events (Phase 4) go through a completely different, stronger mechanism: an append-only `payment_events` audit table, idempotent webhook processing keyed on a provider event id, and row-level locking — because losing a payment event silently would be a correctness bug, not a UX delay. Losing a *push notification* about something that's already safely in Postgres just means the browser finds out a little later, from a normal query, instead of instantly.

**Q: You said graphql-java's execution strategy can run independent field resolvers on different threads. Why does that matter for how you built the stats loader?**
A: I originally considered caching the aggregate query's result in a `@RequestScope` Spring bean so both fields' resolvers could reuse it. That relies on `RequestContextHolder`, which is backed by a `ThreadLocal` bound to the servlet request thread. If graphql-java resolves the `salesCount` and `netEarningsPaise` fields as separate `CompletableFuture`s on a shared thread pool (which its default async execution strategy can do), the second field's resolver might run on a thread that never had that `ThreadLocal` set, and the request-scoped bean would fail to resolve or, worse, silently create a second instance. The `DataLoaderRegistry` Spring for GraphQL passes through `DataFetchingEnvironment` doesn't have that problem — it's an explicit object reference threaded through the call, not a `ThreadLocal`, so it's safe to share across whatever thread each field happens to resolve on.

### "Tell me about a bug you fixed"
**Q: Tell me about a bug you hit while building this.**
A: My first version of the "one shared query" test measured Hibernate's global `SessionFactory.getStatistics().getPrepareStatementCount()` before and after each GraphQL call, and asserted the two measurements were equal. It passed every time I ran it alone, but failed intermittently in the full test suite with an extra statement showing up. The cause was that this app has several `@Scheduled` background tasks — a job-queue poller running every 5 seconds, a viewer-session sweeper — that execute real SQL against the same shared `SessionFactory`, completely unrelated to what I was testing. Under a short, isolated test run the odds of a poll tick landing inside my measurement window were low; under the full, much longer suite run, they weren't. The fix was to stop trusting a JVM-wide counter that anything else running in the same process could pollute, and instead spy on the one repository method I actually cared about with Mockito, asserting it was called exactly once. That's immune to background noise because it only counts calls to that specific method, not all SQL everywhere. Lesson: a global counter is the wrong tool for testing behaviour local to your code path the moment anything else in the process can also move that counter.

---

## 8. Gotchas and bugs we hit

| Symptom | Root cause | Fix | Lesson |
|---|---|---|---|
| `ProductStatsIT`'s "one aggregate query" test failed only when run inside the full `mvn verify` suite, never alone | `Hibernate.Statistics.getPrepareStatementCount()` is global to the `SessionFactory`; background `@Scheduled` pollers (job queue, viewer-session sweeper) run real queries against the same factory, and a longer overall test run gives more chances for a poll tick to land inside the measurement window | Replaced the global-statement-count assertion with a Mockito `@SpyBean` on `OrderItemRepository`, verifying the specific aggregate method ran exactly once | A statistic scoped to the whole process is the wrong tool once anything else in that process can move it; verify the specific call you care about instead |
| `AbstractCommerceIT.initiate()` threw `IllegalArgumentException: Unrecognized Type: EmptyType` when reused for a free product | That shared test helper asserts on `initiateOrder.gatewayOrderId`, which is `null` for a free product (D10 completes it with nothing to charge) — a pre-existing quirk in how the GraphQL test tooling resolves a null field's Java type that only shows up when you actually exercise that path | Skip the shared helper for free-product purchases in new tests; call the mutation directly instead | A shared test helper's untested edge cases are still your problem the first time you're the one to hit them |
| Naively giving `salesCount`/`netEarningsPaise` their own `@BatchMapping` methods (matching the existing `ownedByMe` pattern) would have run the aggregate query twice per dashboard load | `@BatchMapping` registers one `DataLoader` per *field name*, not per underlying query — two fields needing the same query still get two loaders | Registered one named `DataLoader` shared by both fields' `@SchemaMapping` resolvers (§3.4) | The framework's default granularity (per-field) isn't always the granularity your actual cost (per-query) lives at |

---

## 9. New vocabulary

| Term | One-line meaning |
|---|---|
| Server-Sent Events (SSE) | A one-way, server-to-browser push channel over plain HTTP, consumed with the browser's `EventSource` API |
| `SseEmitter` | Spring MVC's server-side handle for writing SSE frames to one open HTTP response over time |
| Redis Pub/Sub | A fire-and-forget broadcast: publishers send to a channel, only currently-subscribed listeners receive it, nothing is stored |
| `GETDEL` | A Redis command that atomically reads a key's value and deletes it — the basis of this phase's single-use ticket |
| `DataLoader` | A per-request batching/caching layer that collects individual `.load(key)` calls and resolves them together in one batch function |
| Named `DataLoader` | A `DataLoader` registered under an explicit string name so multiple, unrelated resolver methods can share the same batch and cache |
| Guarded state transition | A mutation that checks the current state is a *legal* starting point before changing it, rejecting anything else with a specific error |
| At-most-once delivery | A delivery guarantee where a message might be lost but is never duplicated — the opposite of "at-least-once," which webhooks (Phase 4) use instead |

---

## 10. If I had to defend this in a code review

- **Strongest point:** the SSE ticket design and the "re-read from source of truth before pushing" rule in the Redis subscriber. Both are small, deliberate decisions that close real, specific leaks (a JWT in a URL; a payload that could silently drift from the database) rather than generic "best practice" gestures.
- **Also strong:** the shared named `DataLoader` is a genuinely non-obvious technique that solves the exact problem the acceptance criteria asked for ("one aggregate query"), and it's proven with a test that measures the actual mechanism (a spy on the repository call), not a fragile proxy for it.
- **Weakest point, and I'd fix it first:** `SseEmitterRegistry` holds every open connection in a plain in-memory `ConcurrentHashMap` with no upper bound. A malicious or buggy client that opens thousands of connections (or a legitimate traffic spike) has no backpressure or per-user connection cap here — nothing stops one user from opening the stream 500 times. At MVP scale this is a reasonable "build for the load you actually have" call (the same judgment `PreviewController`'s Phase 3 note makes about rate limiting), but it's the first thing I'd add before this went anywhere near real, adversarial traffic: a per-user emitter cap, and a total connection ceiling with a clear rejection (503, not silently accepting and degrading everyone).
