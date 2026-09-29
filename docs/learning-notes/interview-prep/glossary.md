# Glossary

> Every term defined the first time it appears in a phase note lands here. One line each — follow the link for depth.

| Term | Meaning | First seen |
|---|---|---|
| **Bearer token** | "Whoever holds this is authorized"; sent as `Authorization: Bearer <token>` | [Phase 1](../phase-01-auth.md) |
| **BOLA** | Broken Object Level Authorization — acting on records that aren't yours. OWASP API #1 | [Phase 1](../phase-01-auth.md) |
| **CORS** | Browser rules governing which origins may read a response | [Phase 1](../phase-01-auth.md) |
| **CSRF** | Tricking a browser into sending an authenticated request the user didn't intend | [Phase 1](../phase-01-auth.md) |
| **Claim** | A key/value fact inside a JWT (`sub`, `roles`, `exp`) | [Phase 1](../phase-01-auth.md) |
| **Fail closed** | On error, deny access — never grant it | [Phase 1](../phase-01-auth.md) |
| **Filter chain** | The ordered pipeline every HTTP request passes through in Spring Security | [Phase 1](../phase-01-auth.md) |
| **Idempotent** | Doing it twice has the same effect as doing it once | [Phase 1](../phase-01-auth.md) |
| **JWT** | Signed (not encrypted) token carrying identity claims | [Phase 1](../phase-01-auth.md) |
| **Lazy loading** | Hibernate fetching a relation only when touched; fails outside the session | [Phase 1](../phase-01-auth.md) |
| **N+1 query** | 1 query for a list plus N more for each item's relation | [Phase 1](../phase-01-auth.md) |
| **Pessimistic lock** | `SELECT ... FOR UPDATE` — block others until this transaction commits | [Phase 1](../phase-01-auth.md) |
| **RBAC** | Role-Based Access Control | [Phase 1](../phase-01-auth.md) |
| **Reuse detection** | Treating a second use of a one-time token as evidence of theft | [Phase 1](../phase-01-auth.md) |
| **Salt** | Random value mixed into a hash so identical inputs differ | [Phase 1](../phase-01-auth.md) |
| **Stateless auth** | The server keeps no session; the token carries the identity | [Phase 1](../phase-01-auth.md) |
| **Token rotation** | Replacing a refresh token on every use | [Phase 1](../phase-01-auth.md) |
| **User enumeration** | Learning which accounts exist from differing responses | [Phase 1](../phase-01-auth.md) |
| **Work factor** | BCrypt's tunable slowness dial | [Phase 1](../phase-01-auth.md) |
| **Magic Bytes** | The first few bytes of a file that uniquely identify its true format | [Phase 2](../phase-02-upload-pipeline.md) |
| **SKIP LOCKED** | Database-level concurrency control for high-throughput job queues | [Phase 2](../phase-02-upload-pipeline.md) |
| **GIN index** | A PostgreSQL index good at composite/array-like values — used for full-text search | [Phase 3](../phase-03-marketplace.md) |
| **tsvector / tsquery** | PostgreSQL's normalized, searchable document/query representations for full-text search | [Phase 3](../phase-03-marketplace.md) |
| **ts_rank** | Scores how well a `tsvector` matches a `tsquery`, for relevance-ordered search results | [Phase 3](../phase-03-marketplace.md) |
| **HHH000104** | Hibernate's warning for pagination + collection-fetch join in one query — falls back to in-memory paging | [Phase 3](../phase-03-marketplace.md) |
| **Entity graph** | Spring Data JPA's declarative way to eagerly join lazy associations in one query (`@EntityGraph`) | [Phase 3](../phase-03-marketplace.md) |
| **Offset vs. keyset pagination** | `LIMIT`/`OFFSET` ("page N") vs. `WHERE cursor < :last` — simple-but-slower vs. fast-but-no-jump-to-page-N | [Phase 3](../phase-03-marketplace.md) |
| **Presigned URL** | A time-limited, signed URL granting temporary access to one storage object without separate auth | [Phase 3](../phase-03-marketplace.md) |
| **Strategy pattern** | An interface with interchangeable implementations, chosen at runtime — the caller only depends on the interface | [Phase 3](../phase-03-marketplace.md) |
| **Field resolver (GraphQL)** | A method bound to one field, run only when a client's query selects that field | [Phase 3](../phase-03-marketplace.md) |
| **Idempotency key** | Client-generated unique ID sent with a request so the server can recognise a retry and return the original result | [Phase 4](../phase-04-commerce.md) |
| **Check-then-act race** | Two concurrent requests both check a condition before either acts on it — both "win" | [Phase 4](../phase-04-commerce.md) |
| **Optimistic lock** | `@Version` column: everyone proceeds; the second writer fails at commit and must retry | [Phase 4](../phase-04-commerce.md) |
| **Lost update** | Two read-modify-writes where the second silently overwrites the first | [Phase 4](../phase-04-commerce.md) |
| **State machine** | A fixed set of states plus the allowed transitions between them; anything else is rejected | [Phase 4](../phase-04-commerce.md) |
| **Append-only log** | A table that only ever receives INSERTs — a tamper-evident history | [Phase 4](../phase-04-commerce.md) |
| **Webhook** | An HTTP request a provider sends to *your* server when something happens | [Phase 4](../phase-04-commerce.md) |
| **At-least-once delivery** | Messages are retried until acknowledged, so duplicates are guaranteed to happen eventually | [Phase 4](../phase-04-commerce.md) |
| **Effectively-once** | At-least-once delivery + idempotent processing — the practical substitute for exactly-once | [Phase 4](../phase-04-commerce.md) |
| **HMAC** | Hash computed with a secret key — proves a message is unaltered *and* who sent it | [Phase 4](../phase-04-commerce.md) |
| **Timing attack** | Inferring a secret from how long a comparison takes; stopped by constant-time comparison | [Phase 4](../phase-04-commerce.md) |
| **Basis point (bps)** | 0.01% — rates as integers (10% = 1000 bps) so money maths never uses floating point | [Phase 4](../phase-04-commerce.md) |
| **Partial unique index** | A UNIQUE index over only the rows matching a WHERE clause (e.g. only ACTIVE entitlements) | [Phase 4](../phase-04-commerce.md) |
| **Transactional Outbox** | Write outgoing messages in the business transaction; a poller sends them — no lost messages on crash | [Phase 4](../phase-04-commerce.md) |
| **AFTER_COMMIT listener** | `@TransactionalEventListener` that runs only if the transaction commits — for irreversible side effects | [Phase 4](../phase-04-commerce.md) |
| **Propagation MANDATORY** | A `@Transactional` method that must join an existing transaction, or throw | [Phase 4](../phase-04-commerce.md) |
| **Self-invocation trap** | Calling a `@Transactional` method on `this` skips Spring's proxy, so the annotation is ignored | [Phase 4](../phase-04-commerce.md) |
| **Bulkhead** | Separate thread pools/resources per workload so one can't starve another | [Phase 4](../phase-04-commerce.md) |
| **DataLoader / `@BatchMapping`** | Resolve one GraphQL field for many objects in a single batched call — the N+1 fix for field resolvers | [Phase 4](../phase-04-commerce.md) |
| **GraphQL null bubbling** | An error in a non-null field nulls its nearest nullable parent (often the whole `data`) | [Phase 4](../phase-04-commerce.md) |
| **Reconciliation job** | Periodic sweep comparing your records with the provider's to repair drift (e.g. lost webhooks) | [Phase 4](../phase-04-commerce.md) |
| **Singleton container** | One Testcontainers database per JVM (static start), matching Spring's cached test context | [Phase 4](../phase-04-commerce.md) |
| **DRM (traceable deterrence)** | Controls that raise the cost/traceability of copying content — not a claim of "impossible to copy" | [Phase 5](../phase-05-secure-viewer.md) |
| **Presigned URL vs. application-signed URL** | A storage provider's direct link to an object, vs. a URL your own server verifies before doing anything | [Phase 5](../phase-05-secure-viewer.md) |
| **Last-writer-wins** | The newest write replaces the current value (`SET ... GET`) — used where a new login should evict an old one | [Phase 5](../phase-05-secure-viewer.md) |
| **First-writer-wins** | The first write claims the value (`SET NX`); later attempts are rejected — used for single-use tokens | [Phase 5](../phase-05-secure-viewer.md) |
| **Lease** | "Valid until time T unless renewed" — detects an abandoned client without it ever saying goodbye | [Phase 5](../phase-05-secure-viewer.md) |
| **Heartbeat** | A periodic "I'm still here" ping that renews a lease | [Phase 5](../phase-05-secure-viewer.md) |
| **Atomic compare-and-refresh** | Checking a value and conditionally updating it in one indivisible operation (e.g. a Lua script) | [Phase 5](../phase-05-secure-viewer.md) |
| **Watermark** | Identifying information burned into an image's pixels — not removable metadata | [Phase 5](../phase-05-secure-viewer.md) |
| **Injectable Clock** | Passing `java.time.Clock` as a dependency instead of `Instant.now()`, so time-based logic is unit-testable | [Phase 5](../phase-05-secure-viewer.md) |
| **Failsafe plugin** | Maven's integration-test runner, bound to `*IT.java` by convention — distinct from Surefire's `*Test.java` | [Phase 5](../phase-05-secure-viewer.md) |
| **Sweeper** | A `@Scheduled` job that periodically cleans up state a live request path didn't get to | [Phase 5](../phase-05-secure-viewer.md) |
| **`ImageBitmap`** | A decoded, GPU-friendly, DOM-detached image handle from `createImageBitmap()` — must be `.close()`d manually | [Phase 5](../phase-05-secure-viewer.md) |
| **Object URL (`blob:`)** | A live, page-lifetime reference to raw bytes from `URL.createObjectURL` — a "coat-check ticket" for a `Blob` | [Phase 5](../phase-05-secure-viewer.md) |
| **`keepalive` fetch** | A `fetch()` option that lets a request outlive the page unloading it, while still supporting normal headers | [Phase 5](../phase-05-secure-viewer.md) |
| **`sendBeacon`** | A browser API for a guaranteed-to-send exit ping — cannot carry custom headers, so no `Authorization` | [Phase 5](../phase-05-secure-viewer.md) |
| **`pagehide`** | The event that fires when a tab closes, refreshes, or navigates away — reliable, unlike `beforeunload` | [Phase 5](../phase-05-secure-viewer.md) |
| **DevTools-size heuristic** | Inferring DevTools is open from the `outerWidth`/`innerWidth` gap — a guess, not a detector | [Phase 5](../phase-05-secure-viewer.md) |
| **Derived state** | A value computed fresh from other state every render, instead of stored and separately kept in sync | [Phase 5](../phase-05-secure-viewer.md) |
| **Cron expression** | Five-field schedule (min hour day month weekday); always UTC in GitHub Actions | [Ops 1](../ops-01-phase-scheduler.md) |
| **Default branch** | The branch GitHub treats as the repo: scheduled workflows run from it; `Closes #N` only works on merges into it | [Ops 1](../ops-01-phase-scheduler.md) |
| **`GITHUB_TOKEN`** | Per-run token GitHub creates for a workflow; events it causes do not trigger other workflows | [Ops 1](../ops-01-phase-scheduler.md) |
| **PAT (Personal Access Token)** | A token that acts as you — unlike `GITHUB_TOKEN`, its pushes/PRs do trigger workflows | [Ops 1](../ops-01-phase-scheduler.md) |
| **Concurrency group** | GitHub Actions key that makes runs sharing it wait for each other instead of overlapping | [Ops 1](../ops-01-phase-scheduler.md) |
| **Human-in-the-loop** | Automation that pauses for human approval at key points (here: the PR merge) | [Ops 1](../ops-01-phase-scheduler.md) |
| **Reconciliation loop** | Repeatedly compare actual vs desired state and fix the difference — tolerant of missed events | [Ops 1](../ops-01-phase-scheduler.md) |
| **Prompt injection** | Untrusted text (e.g. a stranger's Issue) steering an AI into actions its operator didn't intend | [Ops 1](../ops-01-phase-scheduler.md) |
| **Privilege separation** | Split work so the part handling untrusted input never holds the powerful credential | [Ops 1](../ops-01-phase-scheduler.md) |
| **Git bundle** | A single file containing commits — moves them between machines without a shared remote | [Ops 1](../ops-01-phase-scheduler.md) |
| **WIP limit** | Kanban rule capping how many items are in progress at once (the scheduler's is one) | [Ops 1](../ops-01-phase-scheduler.md) |
| **Server-Sent Events (SSE)** | A one-way, server-to-browser push channel over plain HTTP, read with `EventSource` | [Phase 6](../phase-06-library-dashboard-notifications.md) |
| **`SseEmitter`** | Spring MVC's handle for writing SSE frames to one open HTTP response over time | [Phase 6](../phase-06-library-dashboard-notifications.md) |
| **Redis Pub/Sub** | Fire-and-forget broadcast: only currently-subscribed listeners receive a published message; nothing is stored | [Phase 6](../phase-06-library-dashboard-notifications.md) |
| **At-most-once delivery** | A message might be lost but is never duplicated — the opposite of webhooks' at-least-once | [Phase 6](../phase-06-library-dashboard-notifications.md) |
| **`GETDEL`** | A Redis command that atomically reads and deletes a key — the basis of a single-use ticket | [Phase 6](../phase-06-library-dashboard-notifications.md) |
| **`DataLoader`** | A per-request batching/caching layer that folds individual `.load(key)` calls into one batch function | [Phase 6](../phase-06-library-dashboard-notifications.md) |
| **Named `DataLoader`** | A `DataLoader` registered under an explicit name so multiple resolver methods can share one batch/cache | [Phase 6](../phase-06-library-dashboard-notifications.md) |
| **Guarded state transition** | A mutation that checks the current state is a legal starting point before changing it | [Phase 6](../phase-06-library-dashboard-notifications.md) |
| **Denormalized aggregate** | A derivable value (e.g. `AVG(rating)`) stored again on another table for read speed | [Phase 7](../phase-07-reviews-password-reset.md) |
| **Lost-update anomaly** | Two concurrent read-modify-writes where the second silently overwrites the first | [Phase 7](../phase-07-reviews-password-reset.md) |
| **Upsert** | Insert-if-absent, update-if-present, as one operation | [Phase 7](../phase-07-reviews-password-reset.md) |
| **Data minimisation** | Returning only the fields a feature actually needs, not whatever the type happens to carry | [Phase 7](../phase-07-reviews-password-reset.md) |
| **User enumeration** (see also Phase 1) | Learning which accounts exist from how responses differ, without an explicit yes/no | [Phase 7](../phase-07-reviews-password-reset.md) |
| **Single-use token** | A credential deleted/invalidated the instant it's successfully used once | [Phase 7](../phase-07-reviews-password-reset.md) |
| **Session revocation** | Invalidating already-issued credentials so they stop working immediately | [Phase 7](../phase-07-reviews-password-reset.md) |
| **`INCR`/`EXPIRE` rate limiting** | Redis's atomic counter-with-a-deadline pattern for capping how often something may happen | [Phase 7](../phase-07-reviews-password-reset.md) |
| **ARIA `radiogroup`/`radio`** | Accessibility roles making a custom multi-option picker behave like native radio buttons | [Phase 7](../phase-07-reviews-password-reset.md) |
| **Roving tabindex** | Only one item in a custom widget is Tab-reachable; arrow keys move focus among the rest | [Phase 7](../phase-07-reviews-password-reset.md) |
| **RBAC** (see also Phase 1) | Role-Based Access Control — permission based on a role, not the specific record | [Phase 8](../phase-08-admin-panel.md) |
| **Object-level authorization** | A narrower check on top of RBAC: is *this specific record* the caller's to act on | [Phase 8](../phase-08-admin-panel.md) |
| **Privilege-escalation surface** | Every code path that could let a user end up with more permission than they started with | [Phase 8](../phase-08-admin-panel.md) |
| **Deny-list (token revocation)** | A side list of "these are no longer good," checked alongside an otherwise-stateless credential | [Phase 8](../phase-08-admin-panel.md) |
| **Fail open / fail closed** | On error, allow the request through / deny it — the right default depends on whether the check is the only boundary or a redundant layer | [Phase 8](../phase-08-admin-panel.md) |
| **Post-moderation** | Content goes live first; review (and possible takedown) happens after, only if flagged | [Phase 8](../phase-08-admin-panel.md) |
| **Pre-moderation** | Content is reviewed *before* it becomes visible | [Phase 8](../phase-08-admin-panel.md) |
| **Append-only table** | A table only ever `INSERT`ed into — `UPDATE`/`DELETE` rejected, usually by a DB trigger | [Phase 8](../phase-08-admin-panel.md) |
| **Guarded state transition** (see also Phase 6) | A mutation that checks the record's current state is a legal starting point before changing it | [Phase 8](../phase-08-admin-panel.md) |
| **`Propagation.MANDATORY`** | A `@Transactional` method that must join an existing transaction or throw | [Phase 8](../phase-08-admin-panel.md) |
| **Aggregate query** | `COUNT`/`SUM`/`GROUP BY` computed by the database in one round trip, never by looping over fetched rows in application code | [Phase 8](../phase-08-admin-panel.md) |
| **Reactive null rule** | Reactor `Flux`/`Mono` can't carry `null`; a `@BatchMapping` whose values may be null must return a `Map` (missing key = null), not a `List` | [Phase 6](../phase-06-library-dashboard-notifications.md) |
| **Mobile-first** | Styles target the smallest screen by default; breakpoint prefixes (`md:`) add rules for larger screens | [UI 1](../ui-01-responsive-navigation.md) |
| **Disclosure pattern** | A button that shows/hides a region and announces its state with `aria-expanded` (e.g. a hamburger menu) | [UI 1](../ui-01-responsive-navigation.md) |
| **Canvas backing store** | A canvas's real pixel buffer (`canvas.width/height`), separate from its on-screen CSS size | [Phase 5](../phase-05-secure-viewer.md) |
| **Passive event listener** | A listener that promises not to call `preventDefault()`, so the browser can scroll without waiting on it; React's `onWheel` is passive | [Phase 5](../phase-05-secure-viewer.md) |
| **Single-flight** | Concurrent callers of the same operation share one in-flight request/promise instead of each starting their own (e.g. one token refresh for many 401s) | [Phase 1](../phase-01-auth.md) |
| **RFC 6750 / `invalid_token`** | OAuth 2.0 Bearer Token standard: a bad or expired bearer token gets 401 with `WWW-Authenticate: Bearer error="invalid_token"` | [Phase 1](../phase-01-auth.md) |
| **`StatementInspector`** | Hibernate hook that sees every SQL statement before it runs; used in tests to count one request's queries | [Phase 6](../phase-06-library-dashboard-notifications.md) |
| **Reverse tabnabbing** | A page opened in a new tab uses `window.opener` to redirect the original tab (e.g. to a fake login); prevented by `rel="noopener noreferrer"` | [Phase 5](../phase-05-secure-viewer.md) |
| **PDF user space** | A PDF's coordinate system: points (1/72 inch) with the origin at the bottom-left; must be flipped/rotated to match a rendered image | [Phase 5](../phase-05-secure-viewer.md) |
| **Backfill** | A one-off (or repeating, self-finishing) job that fills in data for records created before a feature existed | [Phase 5](../phase-05-secure-viewer.md) |
| **Correlation id** | One value attached to every log line a single request produces, across every layer and thread it touches | [Phase 9](../phase-09-hardening-release.md) |
| **MDC (Mapped Diagnostic Context)** | SLF4J's per-thread key/value map that every log statement on that thread reads automatically | [Phase 9](../phase-09-hardening-release.md) |
| **Structured logging** | One JSON object per log line, built for a machine to filter/index rather than a human to read as prose | [Phase 9](../phase-09-hardening-release.md) |
| **GraphQL introspection** | The `__schema`/`__type` meta-queries that let a client discover a GraphQL API's entire schema | [Phase 9](../phase-09-hardening-release.md) |
| **Query complexity / query depth** | A numeric cost per selected field (aliases counted separately) / how many levels deep a query's selections nest — two independent GraphQL DoS limits | [Phase 9](../phase-09-hardening-release.md) |
| **Credential stuffing** | Automated, high-volume password guessing against one or many accounts | [Phase 9](../phase-09-hardening-release.md) |
| **Fail-fast configuration** | Refusing to start at all if a known-dangerous precondition (e.g. a dev secret still set in prod) is met, rather than starting and failing later | [Phase 9](../phase-09-hardening-release.md) |
| **Liveness probe / readiness probe** | "Is this process alive?" (restart if not) vs. "should traffic be routed to it right now?" (pull from rotation if not) — two different orchestrator questions | [Phase 9](../phase-09-hardening-release.md) |
| **Testing pyramid** | Many fast unit tests, fewer integration tests, very few slow end-to-end tests — each layer catching what the one below structurally can't | [Phase 9](../phase-09-hardening-release.md) |
| **End-to-end (E2E) test** | A test that drives the real, fully assembled system — here, a real browser against a real running frontend, backend, database and cache, nothing mocked | [Phase 9](../phase-09-hardening-release.md) |
| **Requirements traceability matrix** | A document mapping every requirement id to the specific evidence (test or code) proving it's implemented | [Phase 9](../phase-09-hardening-release.md) |
| **HSTS (HTTP Strict Transport Security)** | A response header telling the browser to only ever connect to this origin over HTTPS from now on | [Phase 9](../phase-09-hardening-release.md) |
| **Permissions-Policy** | A response header disabling specific browser APIs (camera, microphone, geolocation, payment) for the page | [Phase 9](../phase-09-hardening-release.md) |
| **Port / adapter (hexagonal architecture)** | An interface the core logic depends on (`PaymentGateway`), plus the implementations that connect it to the outside world (mock, Razorpay) | [Phase 9B](../phase-09b-razorpay.md) |
| **Test mode / live mode** | Razorpay's two environments: fake money with `rzp_test_` keys, real money with `rzp_live_` keys | [Phase 9B](../phase-09b-razorpay.md) |
| **Authorize vs capture** | Authorize = the bank reserves the money; capture = we claim it. An uncaptured authorization is released after a few days | [Phase 9B](../phase-09b-razorpay.md) |
| **Auto-capture** | A Razorpay account setting that captures every authorized payment immediately | [Phase 9B](../phase-09b-razorpay.md) |
| **Reconciliation** | Comparing our records with the provider's to find and repair disagreements (a paid order we never heard about) | [Phase 9B](../phase-09b-razorpay.md) |
| **Refund (as a state transition)** | Order/payment move to `REFUNDED` and the entitlement to `REVOKED`; nothing is deleted | [Phase 9B](../phase-09b-razorpay.md) |
| **Gateway fee (MDR)** | What the payment provider charges the merchant per payment (about 2% plus GST on that fee) | [Phase 9B](../phase-09b-razorpay.md) |
| **`MockRestServiceServer`** | Spring test utility that replaces a remote HTTP server so a test can assert the exact requests sent and return canned responses | [Phase 9B](../phase-09b-razorpay.md) |
| **HTTP Basic auth** | An `Authorization` header carrying `base64(user:password)` — for Razorpay, `key_id:key_secret` | [Phase 9B](../phase-09b-razorpay.md) |
| **Receipt (gateway)** | Our own reference (`sl_order_42`) sent to the gateway and echoed back — a gateway-side idempotency handle | [Phase 9B](../phase-09b-razorpay.md) |
| **checkout.js / `frame-src`** | Razorpay's browser script that opens the payment popup in an iframe; the CSP must allow its script, frame and API hosts explicitly | [Phase 9B](../phase-09b-razorpay.md) |
| **Ledger** | A record of events (credits/debits) from which a balance is computed, instead of a stored balance that must be kept in sync | [Phase 9C](../phase-09c-payouts-legal.md) |
| **Hold period** | Days a sale's earnings stay "pending" before they can be withdrawn; set equal to the refund window so refundable money isn't paid out | [Phase 9C](../phase-09c-payouts-legal.md) |
| **Check-then-act race** | Two concurrent requests both pass a check ("is there an open payout?") before either acts, so both succeed | [Phase 9C](../phase-09c-payouts-legal.md) |
| **Partial unique index** | A unique index limited to rows matching a `WHERE` — e.g. one *open* payout per creator | [Phase 9C](../phase-09c-payouts-legal.md) |
| **Snapshot (of a value)** | Copying a value onto a record when it matters (payout destination) so later edits can't rewrite history | [Phase 9C](../phase-09c-payouts-legal.md) |
| **Manual payout** | Money is sent outside the app (UPI/bank) and recorded afterwards with a transfer reference | [Phase 9C](../phase-09c-payouts-legal.md) |
| **CSV injection** | A spreadsheet formula (`=`, `+`, `-`, `@` prefix) hidden in exported data that runs when the file is opened | [Phase 9C](../phase-09c-payouts-legal.md) |
| **`ALTER TYPE … ADD VALUE`** | Adds a Postgres enum value; the value can't be used in the same transaction, so it gets its own migration | [Phase 9C](../phase-09c-payouts-legal.md) |
| **DPDP Act 2023 / data fiduciary** | India's data-protection law and its term for whoever decides how personal data is processed | [Phase 9C](../phase-09c-payouts-legal.md) |
| **`body.sl-reading` / `.no-print`** | The CSS hooks for printing: blank the page only while the secure reader is mounted; hide site chrome on paper | [Phase 9C](../phase-09c-payouts-legal.md) |
