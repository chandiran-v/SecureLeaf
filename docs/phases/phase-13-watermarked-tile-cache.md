# Phase 13 — Watermarked tile cache

> Spec for an unattended run. Decisions are numbered (D1…) and referenced from code comments.
> Requirements: **MVP2-02**.
> Depends on: Phase 12. Target branch: `feature/secure-leaf-mvp2`.

## Context

Readers flip back and forth, and each revisit re-renders an identical watermarked tile. `requirements.md` estimates a ~40% hit rate on back-navigation. Caching the **already-watermarked** bytes per buyer saves both the storage fetch and the render. The clean tile must still never be cached anywhere the browser can reach.

## Decisions

- **D1 — Make the watermark cache-stable.** The 05A label includes a minute-level timestamp, which would make every render unique and the cache useless. Change it to `"{email} · #{userId} · {yyyy-MM-dd} UTC · s{sessionId}"`: **date + session id**, which still identifies the buyer and the viewing session forensically.
  - The cache key includes `sessionId`, so a new session produces a new render.
  - Record this forensic trade-off (minute → date + session precision) in the note, and why it's acceptable: the access log still has exact per-page timestamps joined by session id.
- **D2 — Key.** `sha256(userId | sessionId | documentVersionId | pageNumber | variant | rendererId | watermarkVersion)`. `watermarkVersion` is a config constant; bumping it invalidates everything. `variant` is `default` until Phase 16.
- **D3 — Strategy interface.** `WatermarkedTileCache { Optional<byte[]> get(key); void put(key, bytes); void evictUser(userId); }` with:
  - `DiskTileCache` (the default for a single server): files under `tilecache.dir` (default `${java.io.tmpdir}/tile-cache`), with an in-memory Caffeine index for TTL and total-size bound. Default TTL 15 min, max 2 GB. Write atomically (temp file + rename) and delete on eviction.
  - `RedisTileCache` (for horizontal scale): binary values, `EX 900`, optional `maxBytesPerEntry`.
  - `NoopTileCache`, for tests and to disable.

  `tilecache.type: disk | redis | none`. The note carries the RAM maths from `requirements.md` (5,000 users × 10 pages × ~500 KB) and explains why disk is the default.
- **D4 — Where the cache sits.** The **access checks from 05A always run first.** The cache is looked up only after the request is fully authorised. The rate limit still applies. A cache hit **still writes the access-log row** (VIEW-13: it's a view). A hit skips the storage fetch and the render.
- **D5 — Invalidation.**
  - Entitlement revoked, or user suspended: `evictUser(userId)`. It is best-effort, and the entitlement check still protects because it runs before the lookup.
  - Session superseded: its entries age out; the key includes the session id.
  - Renderer or watermark change: bump the version.
- **D6 — Response headers stay `no-store`.** This is a server-side cache only. The browser must not cache tiles.
- **D7 — Metrics.** Hit and miss counters, `secureleaf.tilecache.size.bytes`, and eviction counts. Add a hit-rate panel to Grafana.

## Acceptance criteria
1. The second request for the same page in the same session is a hit: the renderer and storage spies are **not** called, and the bytes are identical.
2. A different session, or a different user, is a miss and gets different bytes.
3. After revocation, the next request gets 403 even though a cache entry exists (checks run before the cache).
4. A cache hit still writes an access-log row.
5. Disk cache: TTL expiry (fake ticker), the size bound evicts the oldest entries and deletes their files, a concurrent put of the same key doesn't corrupt it (parallel test), and restarting with a stale directory is safe.
6. `tilecache.type=none` behaves exactly like Phase 12.
7. The Phase 10 k6 run with back-navigation shows the hit rate and the p95 change, recorded in `docs/perf/`.

## Out of scope
- A CDN.
- Caching clean tiles.
- Cross-user deduplication (it would break per-buyer watermarking by definition).

## Learning note
Create `docs/learning-notes/phase-13-watermarked-tile-cache.md`. Headline topics:
- cache-aside
- key design and versioned keys for invalidation
- "there are only two hard things…" (invalidation)
- security ordering: authorise before cache lookup, and the classic bug of a cache that bypasses auth
- the disk vs Redis vs in-heap trade-offs with real numbers
- atomic file writes
- hit-rate economics
