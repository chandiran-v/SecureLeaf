# Phase 06 — Library, creator dashboard & live notifications

> Spec for an unattended run. Decisions are numbered (D1…) and referenced from code comments.
> Requirements: **LIB-01..03, DASH-01..04, UPLOAD-09, UPLOAD-10, NOTIF-01..04**.
> Depends on: Phase 05B (the viewer exists, so "Read" works).

## Context

The pieces exist but don't yet form a product:
- The library lists entitlements.
- The creator dashboard lists products, with unpublish and delete wired in.
- Notifications are stored, and published to Redis Pub/Sub (`RedisNotificationPublisher`).
- The bell **polls every 30 s** (`NotificationBell.tsx`).

Gaps:
- no per-product sales stats
- no way to recover a FAILED upload
- no processing-complete or processing-failed notifications (the enum values `PROCESSING_COMPLETE` / `PROCESSING_FAILED` exist but nothing emits them)
- no real-time delivery

## Decisions

- **D1 — Library (LIB-01..03).** `myLibrary` returns *all* of the buyer's entitlements, not only ACTIVE ones, newest first. The UI shows:
  - cover, title, creator display name, purchase date, and a status badge: Active, Revoked, or Expired (the existing `StatusBadge`)
  - **Read** for ACTIVE, and a disabled button with the reason otherwise
  - client-side search by title, and an empty state that links to the marketplace

  Resolve fields with the existing batching pattern. No N+1: add a query-count assertion like `MarketplaceQueryIT`.
- **D2 — Per-product stats (DASH-02).** Add `Product.salesCount: Int!` and `Product.netEarningsPaise: Long!`. They are visible only to the product's creator; others get `null`, so make the fields nullable in the schema.
  - Resolve them with **one** `@BatchMapping` aggregate query over `order_items` joined to COMPLETED `orders` for all products in the response.
  - Use the snapshotted `creator_earnings_paise` from Phase 4 (D7 there). Never recompute from the current fee.
- **D3 — Earnings summary (DASH-03)** reuses `creatorEarnings`. It shows gross, platform fee and net as cards, formatted with the existing `formatPrice`.
- **D4 — Status and recovery (DASH-01, UPLOAD-10).** The dashboard shows PROCESSING (with the current job stage), LIVE, UNPUBLISHED and FAILED (with `failure_reason`). New mutations, all owner-only and checked in the service layer (object-level authorisation):
  - `retryProcessing(productId)`: only for FAILED. Resets the job to QUEUED with `retry_count = 0` and product status PROCESSING. Anything else throws `INVALID_STATE_TRANSITION`.
  - `republishProduct(productId)`: UNPUBLISHED → LIVE, only if processing completed and it isn't soft-deleted.
- **D5 — Unpublish vs delete (UPLOAD-09).** Keep the current semantics. Both actions ask for confirmation in a modal, and the modal says existing buyers keep access. Add an integration test: after `deleteProduct`, the buyer still sees it in `myLibrary` and can still start a viewer session.
- **D6 — Emit processing notifications (NOTIF-03).** When the pipeline marks a job COMPLETED (product LIVE), notify the creator with `PROCESSING_COMPLETE`. When it gives up after max retries, send `PROCESSING_FAILED`. Use the existing `NotificationService` (AFTER_COMMIT path) so no notification goes out for a rolled-back state.
- **D7 — Real-time delivery with Server-Sent Events (NOTIF-04).**
  - **Why SSE and not WebSocket:** delivery is one-way (server → client), it runs over plain HTTP, the browser reconnects automatically, and Spring MVC supports it out of the box (`SseEmitter`). WebSocket/STOMP adds a protocol and a broker config we don't need. The learning note records this trade-off.
  - Endpoint: `GET /api/notifications/stream?ticket=…` produces `text/event-stream`.
  - **Auth via a one-time ticket.** `EventSource` can't send an `Authorization` header, and putting the JWT in the URL would leak it into logs and history. So:
    1. GraphQL `notificationStreamTicket: String!` (authenticated) stores `SET sse-ticket:{random} {userId} EX 30`.
    2. The stream endpoint consumes the ticket with `GETDEL`. It is single-use.
  - **Fan-out:** each backend instance subscribes (`RedisMessageListenerContainer`) to the per-user channel pattern `RedisNotificationPublisher` already uses. It pushes to the emitters held *locally* for that user, in a `ConcurrentHashMap<Long, Set<SseEmitter>>`. This works with multiple instances because every instance hears every message.
  - Send a heartbeat comment `:\n\n` every 25 s, so proxies don't close idle streams. Emitter timeout is 30 min. On completion or error, remove the emitter.
  - Event payload: the `NotificationDto` as JSON, event name `notification`.
- **D8 — Frontend bell.** Use `EventSource` with a fresh ticket per (re)connect: fetch the ticket, then open the stream. On an event, update the Apollo cache (prepend to `myNotifications`, bump the unread count) and show a small toast.
  - Keep polling only as a fallback: after 3 failed reconnects, poll every 60 s.
  - Add a **"Mark all read"** action: mutation `markAllNotificationsRead: Int!` returns the number updated.
- **D9 — Tests need Redis.** Phase 05A adds the Redis Testcontainer. The SSE integration test must use the *real* Redis publisher for that test (e.g. a nested `@TestConfiguration`, or a profile that doesn't register `RecordingNotificationPublisher`), because it verifies the Pub/Sub → SSE hop end to end.

## Acceptance criteria
1. `myLibrary` includes REVOKED entitlements with the correct status, and has a bounded query count.
2. `salesCount`/`netEarningsPaise` are correct after two purchases, `null` for a non-owner, and resolved in one aggregate query.
3. `retryProcessing` on FAILED → QUEUED, then processed to LIVE (with a pipeline stub that fails once, then succeeds). On a LIVE product it throws `INVALID_STATE_TRANSITION`. A non-owner gets `ACCESS_DENIED`.
4. `republishProduct` makes an UNPUBLISHED product visible in the marketplace again.
5. A deleted or unpublished product stays in the buyer's library and remains viewable.
6. A completed pipeline creates a `PROCESSING_COMPLETE` notification. Exhausted retries create `PROCESSING_FAILED`.
7. SSE works end to end: ticket → connect → a purchase triggers `SALE_RECEIVED` → the creator's stream receives it within 5 s. A reused ticket gets 401. A missing ticket gets 401.
8. Frontend tests:
   - Library renders statuses and the Read/disabled buttons.
   - The dashboard shows stats and FAILED + Retry.
   - The bell prepends an incoming SSE event, using a mocked `EventSource`.
   - "Mark all read" clears the badge.

All existing tests stay green.

## Out of scope
- Email template redesign.
- Push notifications.
- Payouts (post-MVP).
- Charts and analytics beyond the stat cards.

## Learning note
Create `docs/learning-notes/phase-06-library-dashboard-notifications.md`. Headline topics:
- SSE vs WebSocket vs polling, and why we picked SSE
- one-time tickets for authenticating streams
- Redis Pub/Sub fan-out across instances, and its at-most-once caveat (why the DB row stays the source of truth)
- `@BatchMapping` aggregates
- state transitions as guarded operations
- soft delete preserving buyer rights
