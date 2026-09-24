# Phase 05A — Secure viewer (backend)

> Spec for an unattended run. Decisions are numbered (D1…) and referenced from code comments.
> Requirements: **VIEW-01, VIEW-02, VIEW-03, VIEW-10, VIEW-12, VIEW-13** (`docs/requirements.md`).
> Depends on: Phase 4 (entitlements). Followed by: Phase 05B (the canvas viewer UI).

## Context

After Phase 4 a buyer can pay and holds an ACTIVE `entitlement`, but there's nothing to read with.
This phase builds the **server side of the DRM viewer**:
- viewer **sessions**, with only one active session per buyer per product
- **signed, single-use, 30-second tile URLs**
- per-request **watermark burning** with the buyer's identity
- an **audit log** row for every page viewed

The browser must **never** receive a clean tile. The same rule held for the Phase 3 preview (`PreviewService`).

### Verified starting facts
| Fact | Evidence |
|---|---|
| `viewer_sessions`, `viewer_access_logs` tables exist; entities exist, no services | `V1__init_schema.sql:216-251`, `viewer/entity/*` |
| `ip_address` columns are `VARCHAR(45)` | `V2__fix_ip_address_type.sql` |
| `WatermarkRenderer` Strategy + `Java2DWatermarkRenderer` exist | `content/watermark/*` |
| Preview serves watermarked PNG bytes, never presigned tile URLs | `PreviewService.java` |
| `drm.signed-url-ttl-seconds: 30` and `drm.watermark.*` config exist | `application.yml` |
| One ACTIVE entitlement per buyer+product (partial unique index) | `V4__commerce_integrity.sql` |
| Integration tests don't start Redis (publisher is replaced) | `RecordingNotificationPublisher.java` |

## Decisions

- **D1 — Session lifecycle over GraphQL, tile bytes over REST.** This follows the CLAUDE.md rule: GraphQL for data, REST for files.
  - `startViewerSession(productId: ID!, deviceFingerprint: String!): ViewerSession!` returns `{ sessionId, sessionToken, productId, pageCount, heartbeatIntervalSeconds, expiresAt }`.
  - `viewerHeartbeat(sessionToken: String!): ViewerHeartbeat!` returns `{ status: ACTIVE | SUPERSEDED | EXPIRED, expiresAt }`.
  - `endViewerSession(sessionToken: String!): Boolean!`
  - `viewerPageUrl(sessionToken: String!, pageNumber: Int!): SignedPageUrl!` returns `{ url, expiresAt }`.
- **D2 — Session token.** It is 256 bits from `SecureRandom`, Base64URL-encoded. Only its SHA-256 hash is stored (`viewer_sessions.session_token_hash`). This is the same reasoning as refresh tokens in Phase 1: a database leak must not give anyone working sessions.
- **D3 — One active session per buyer+product, last writer wins.** VIEW-10 says opening the viewer on a second device **terminates the first**. That makes `SET NX` (first writer wins) the *wrong* primitive for the active-session pointer.
  - Use Redis `SET viewer:active:{userId}:{productId} {sessionId} EX 45 GET`. It is atomic and returns the previous session id.
  - If there was a previous session, mark its row `ended_at = now`, `end_reason = 'SUPERSEDED'`.
  - The learning note must explain why NX would be wrong here, and where NX *is* right (D5).
- **D4 — Heartbeat = lease.** The client heartbeats every **15 s**. Each heartbeat refreshes the Redis key's TTL to **45 s** and updates `last_heartbeat_at`, but only if the key still holds *this* session id. The compare-and-refresh must be atomic, so use a small Lua script.
  - If another session holds the key, respond `SUPERSEDED`.
  - If the key is missing, respond `EXPIRED`.
  - A `@Scheduled` sweeper runs every 60 s and ends DB rows whose `last_heartbeat_at < now - 45 s` with `end_reason = 'EXPIRED'`.
- **D5 — Signed tile URL.** The URL is `/api/viewer/tiles/{sessionId}/{pageNumber}?exp={epochSeconds}&sig={base64url}`, where `sig = HMAC-SHA256(drm.signing-secret, "{sessionId}|{pageNumber}|{userId}|{exp}")`. `exp` is at most now + `drm.signed-url-ttl-seconds` (30).
  - URLs are **single-use**: on first use, `SET viewer:used-sig:{sig} 1 NX EX 60`. If NX fails, return 403. This is where first-writer-wins *is* the right choice.
  - Compare signatures with `MessageDigest.isEqual` (constant time), as the Phase 4 webhook code does.
- **D6 — The tile endpoint checks everything, in this order.**
  1. JWT present (401).
  2. Signature valid and not expired (403).
  3. Single-use (403).
  4. The JWT user equals the signed `userId` (403).
  5. The session is the active one in Redis (409 `VIEWER_SESSION_SUPERSEDED`, or 410 `VIEWER_SESSION_EXPIRED`).
  6. The entitlement is still ACTIVE (403).
  7. The page is in range for the **entitled** `document_version_id` (404).

  Then: load the clean tile from storage, burn the watermark, and return `image/png`. Set `Cache-Control: no-store, private`, `Pragma: no-cache`, `X-Content-Type-Options: nosniff`, and no `Content-Disposition`. Checks 1 and 4 together mean a leaked URL is useless to anyone else, even inside its 30 s window.
- **D7 — The watermark identifies the buyer.** The label is `"{email} · #{userId} · {yyyy-MM-dd HH:mm} UTC · s{sessionId}"`. Reuse `WatermarkRenderer` unchanged. The preview keeps its static label. A repeated/tiled watermark pattern is out of scope; note it as a follow-up.
- **D8 — Unpublished products stay readable** for existing buyers (UPLOAD-09). Soft-deleted products do too. Only the entitlement status decides access. `LIVE` status is **not** required. This is the opposite of the preview rule, and it needs a test.
- **D9 — Access log (VIEW-13).** Every *successful* tile response inserts one `viewer_access_logs` row in the same request: session, user, product, version, content page, page number, IP, user agent, correlation id.
  - Take the correlation id from the `X-Correlation-Id` request header, or generate a UUID. Echo it back in the response header.
  - Use a synchronous insert. At MVP scale that is cheaper than the complexity of batching; the learning note says when it would change.
- **D10 — Schema.** Add `V5__viewer_sessions.sql`: `ALTER TABLE viewer_sessions ADD COLUMN end_reason VARCHAR(20)` with a CHECK constraint for `('CLOSED','SUPERSEDED','EXPIRED','REVOKED')`. Never edit V1–V4.
- **D11 — Configuration** (`application.yml`, all overridable by environment variable):
  ```yaml
  drm:
    signing-secret: ${DRM_SIGNING_SECRET:dev-only-change-me-32-bytes-minimum!!}
    session:
      heartbeat-interval-seconds: 15
      lease-seconds: 45
  ```
  In the `prod` profile, refuse to start if `signing-secret` is still the dev default. That is the same pattern as the payment gateway guard.
- **D12 — Errors.** Add these `ErrorCode`s: `NOT_ENTITLED` (FORBIDDEN), `VIEWER_SESSION_SUPERSEDED`, `VIEWER_SESSION_EXPIRED`, `SIGNED_URL_INVALID`. GraphQL errors go through the existing `GlobalGraphQlExceptionHandler`. REST errors go through `GlobalRestExceptionHandler`, with a JSON body and the status codes from D6.
- **D13 — Security config.** `/api/viewer/**` requires authentication and is `GET` only.
- **D14 — Tests need real Redis.** Add a singleton `GenericContainer("redis:7-alpine")` to `AbstractIntegrationTest`, next to the Postgres container, and register `spring.data.redis.*` via `@DynamicPropertySource`. Keep `RecordingNotificationPublisher` as it is.

## Backend work (package `com.secureleaf.viewer`)
- `ViewerSessionService`: start, heartbeat, end, supersede, and the sweeper (`@Scheduled`; enable scheduling if it isn't already).
- `TileUrlSigner`: sign and verify (pure, unit-testable, with an injectable `Clock`).
- `SecureTileService`: the D6 checks, then load the tile and watermark it. Reuse `StorageService` + `WatermarkRenderer`.
- `ViewerAccessLogService`: the D9 insert.
- `ViewerResolver` (GraphQL) and `SecureTileController` (REST).
- Repositories for `ViewerSession` / `ViewerAccessLog`. Extend the entities if they are missing columns.
- `schema.graphqls`: the types and operations from D1, plus the `ViewerSessionStatus` enum.

## Frontend work
None in this phase apart from TypeScript types and GraphQL documents for the new operations (`src/graphql/…`, `src/types`). They don't need wiring into any page yet; Phase 05B builds the UI.

## Acceptance criteria (integration tests; `ViewerIT` and similar)
1. A user without an ACTIVE entitlement gets `NOT_ENTITLED` from `startViewerSession`.
2. The happy path works: start → `viewerPageUrl` → GET the tile returns 200 `image/png`. The bytes **differ** from the clean stored tile, and response headers include `Cache-Control: no-store`.
3. The same signed URL used a second time returns 403.
4. An expired URL (use a `Clock` fixture) returns 403. A tampered `sig`, `pageNumber` or `sessionId` returns 403.
5. A valid URL fetched with **another user's** JWT returns 403.
6. After a second `startViewerSession` for the same buyer+product, the first session's heartbeat returns `SUPERSEDED`, its tile fetch returns 409, and its DB row has `end_reason = SUPERSEDED`.
7. If the lease lapses (key removed or expired), the heartbeat returns `EXPIRED`, and the sweeper ends the row.
8. Revoking the entitlement mid-session makes the next tile fetch return 403.
9. A page number outside `1..pageCount` returns 404.
10. An **UNPUBLISHED** product is still viewable by an entitled buyer (D8).
11. Each successful tile creates exactly one `viewer_access_logs` row with the correct page and correlation id. Failed fetches create none.
12. An unauthenticated tile request returns 401.
13. Unit tests for `TileUrlSigner` cover the sign/verify round-trip, expiry, tampering, and the constant-time compare.

All existing tests stay green.

## Out of scope
- The canvas UI and browser friction controls (05B).
- Rate limiting (Phase 11), tile caching (Phase 13), and multi-resolution tiles (Phase 16).
- A tiled watermark pattern.
- Creators viewing their own product in the viewer without an entitlement. List it as a follow-up.

## Learning note
Create `docs/learning-notes/phase-05-secure-viewer.md` (Phase 05B extends it). Headline topics:
- the DRM threat model: what we stop, what we can't (screen recording), and traceable deterrence
- HMAC-signed URLs vs presigned storage URLs
- single-use tokens (`SET NX`) vs last-writer-wins (`SET … GET`)
- leases and heartbeats
- constant-time comparison
- append-only audit logs
- defense in depth: why JWT *and* signature *and* session *and* entitlement

Interview Q&A should include "Why not just give the browser a presigned MinIO URL?"
