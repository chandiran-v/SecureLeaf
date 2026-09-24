# SecureLeaf — Interview Quick Reference

> The cram sheet. One page per phase, growing as the project grows. Read `../phase-XX-*.md` for the depth behind each line.

---

## The 60-second project pitch

> "SecureLeaf is a DRM-protected marketplace for digital documents. Creators upload PDFs; buyers purchase and read them inside a secure browser viewer, never as a downloadable file. Server-side, each PDF is converted into per-page image tiles by a PostgreSQL-backed async job queue. When a buyer views a page, the tile is fetched, a watermark carrying their identity is burned into it in-memory with Java2D, and it's served over a signed URL that expires in 30 seconds. The browser never receives a clean tile. It's Java 21 / Spring Boot 3 with GraphQL, PostgreSQL, Redis and MinIO, and a React + TypeScript frontend."

**If they ask "why is that hard?"** — three things: the browser must never hold an unwatermarked image, so watermarking happens per-request rather than at upload; tile serving has a sub-500ms budget with watermark burning on the hot path; and access is enforced at the level of individual records, not just roles.

**The honest framing of the DRM:** it is *traceable piracy deterrence*, not absolute prevention. No browser solution can stop a phone camera. Every leaked page carries a watermark identifying the buyer it came from. Same model as Kindle or Spotify. Saying this unprompted signals engineering maturity — claiming "uncrackable DRM" signals the opposite.

---

## Phase 1 — Auth · [full note](../phase-01-auth.md)

| Concept | The one-line answer |
|---|---|
| Two tokens | 15-min JWT access (stateless, unrevocable) + 7-day DB-backed refresh (revocable) |
| BCrypt vs SHA-256 | Slow+salted for guessable human passwords; fast for 128-bit random tokens |
| Rotation | Refresh tokens are single-use; reuse = theft → revoke all sessions |
| 30s grace window | Multiple browser tabs racing a refresh aren't attackers |
| CSRF off | Safe *because* we use `Authorization` headers, not cookies |
| Google OAuth | Verify signature + issuer + **audience**; we never see a password |
| `ddl-auto: validate` | Flyway owns the schema; Hibernate only checks for drift |
| AuthN vs AuthZ vs BOLA | Who are you / what may you do / is *this record* yours |

**Weakest point to volunteer:** tokens in localStorage — XSS-exposed. Correct fix is httpOnly cookies + CSRF tokens.

---

## Phase 2 — Upload & async pipeline · [full note](../phase-02-upload-pipeline.md)

| Concept | The one-line answer |
|---|---|
| `FOR UPDATE SKIP LOCKED` | Lets many workers pull from one queue table without blocking or double-processing |
| Why Postgres, not Kafka | ~10 uploads/day doesn't justify brokers, partitions and Zookeeper ops |
| 202 Accepted | Upload returns immediately; the work happens async — don't hold an HTTP thread for 30s |
| Idempotent stages | Every stage is re-runnable, because retries replay it |
| Magic-byte validation | Trust the file's first bytes, never the client's `Content-Type` |
| `assertOwnership` | The object-level check `@PreAuthorize` can't do |

**Weakest point to volunteer:** the worker polling interval is fixed. Event-driven triggers like Postgres LISTEN/NOTIFY would be more responsive and efficient.

---

## Phase 3 — Marketplace · [full note](../phase-03-marketplace.md)

| Concept | The one-line answer |
|---|---|
| FTS predicate (verbatim) | `to_tsvector('english', title \|\| ' ' \|\| description) @@ plainto_tsquery('english', :q)` — must match the GIN expression index exactly or it silently seq-scans |
| Why native SQL, not Criteria/QueryDSL | Criteria may rewrite the FTS expression and drop the index silently; native SQL is honest here |
| The 3-query paging recipe | 1) `SELECT id ... LIMIT/OFFSET` (no join) 2) `COUNT(*)` same WHERE 3) `@EntityGraph findAllByIdIn(ids)` (no LIMIT) — then re-order in Java to step 1's order |
| `HHH000104` | Pagination + collection-fetch join in one query → Hibernate silently loads everything into memory to paginate there |
| `@SchemaMapping` snippet | `@SchemaMapping(typeName = "Product", field = "thumbnailUrl")` — computed only if the client selects the field |
| Presigned thumbnails, never presigned tiles | Thumbnails are public marketing assets (1h TTL); tiles are protected content — always streamed watermarked, never signed |
| 404 not 403 | Hidden/out-of-range resources return "not found," never "forbidden" — a 403 would confirm the resource exists |
| Sort whitelist | `sortBy` matched against a fixed `switch`, never interpolated into SQL — closes the injection door even in hand-built SQL |

**Weakest point to volunteer:** the free-preview endpoint is public and does real CPU work (watermark render) per request with only a page-range check as protection — no rate limiting yet (deferred to MVP-2), and the optional read-through cache wasn't built in this pass.

---

## Phase 4 — Commerce · [full note](../phase-04-commerce.md)

| Concept | The one-line answer |
|---|---|
| Order vs payment vs entitlement | Intent / money movement / access grant — separate so a failed payment never touches access and a free product needs no payment |
| Double-click → one order | 3 layers: client idempotency key (UUID per page visit) → reuse open PENDING order → `FOR UPDATE` on the buyer row makes check-then-act atomic |
| Browser + webhook race | Both lock the order row; the second sees COMPLETED and no-ops. One `applyCapture` path |
| Lock *first*, then read | Loading then locking returns Hibernate's cached, stale object — check state only on the locked row |
| Webhook = source of truth | The browser can close/time out; the webhook is server-to-server and retried until 2xx |
| At-least-once → effectively-once | Dedup by `provider_event_id` (UNIQUE) + idempotent transitions; return 2xx on duplicates |
| HMAC, not SHA-256 | A plain hash proves integrity; only a keyed hash proves origin. Sign the **raw bytes**; compare with `MessageDigest.isEqual` |
| State machine | `canTransitionTo` on the enum; status has no setter, only `transitionTo`; decline ≠ failed order |
| Append-only audit | Every transition writes `payment_events`; a DB trigger rejects UPDATE/DELETE |
| `total_sales + 1` in SQL | Avoids lost updates *and* `@Version` conflicts that would roll back a paid purchase |
| Money | Paise integers; fee in basis points, round half-up; earnings = price − fee; snapshot the split per order item |
| DB backstop | `UNIQUE (buyer_id, product_id) WHERE status='ACTIVE'` — holds even if Java is wrong |
| AFTER_COMMIT + @Async | Never email about a purchase that rolled back; slow SMTP never blocks checkout |
| Timeout ≠ failure | Money may be taken — tell the buyer not to pay again, poll, let the webhook decide |
| Mock can't reach prod | Prod provider = `razorpay`; no implementation → app refuses to start (fail fast) |

**Weakest point to volunteer:** no reconciliation job — a webhook lost beyond the provider's retry window leaves a paid order PENDING. Fix: a scheduled sweep that asks the gateway about stale PENDING orders and feeds captures through the same idempotent path. Also: emails can be lost on a crash between commit and send (fix: Transactional Outbox).

---

## Cross-cutting themes to weave into any answer

1. **Threat-model each decision.** Every security choice here has a "what attack does this stop" answer. Say it.
2. **Name what you gave up.** Every choice has a cost; stating it is what separates a senior answer from a memorized one.
3. **Defense in depth.** Validate in the DTO, enforce in the service, constrain in the database.
4. **Design for concurrency.** The pessimistic lock, `SKIP LOCKED`, the grace window and Phase 4's order-row lock all exist because two things happen at once in production.
5. **Fail closed.** Errors deny access; they never grant it.
6. **Every message arrives twice, eventually.** Double-clicks, retries, webhook redelivery — make the second one harmless (idempotency) instead of hoping it won't come.
