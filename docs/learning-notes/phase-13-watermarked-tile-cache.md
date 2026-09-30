# Phase 13 — Watermarked tile cache

> **Status:** Done (k6 hit-rate measurement not run — see `../perf/phase-13-tile-cache.md`)
> **Built:** 2026-09-30
> **Requirement IDs covered:** MVP2-02 (spec: `docs/phases/phase-13-watermarked-tile-cache.md`)
> **Commits:** see `git log --grep "Phase 13"`

---

## 1. What we built, in plain English

When a reader flips to page 4 and then back to page 3, the server used to do all the work again: fetch the clean page image from storage, paint the buyer's name across it, and send it. Now the server remembers the *finished, already-watermarked* picture for a short while (15 minutes) and hands it straight back. The reader sees no difference, but the server skips the two most expensive steps.

The cache is careful about three things. It is only consulted **after** the server has decided the reader is allowed to see the page. It is **per buyer and per viewing session**, so one buyer's watermarked page can never be given to another buyer. And it lives only on the server — the browser is still told "never store this".

**Before this phase:** every tile request fetched from storage and re-rendered, even a page the same reader saw ten seconds ago.
**After this phase:** a repeat view in the same session is a cache hit; the storage fetch and the render are skipped. Access logging is unchanged (a hit still counts as a view).

---

## 2. Why it matters

Rendering is the CPU-bound step (Phase 10 baseline ~85 ms/tile; Phase 12 gave it a bounded pool). The cheapest render is the one you don't do. `requirements.md` estimates ~40 % of requests are back-navigation, so a good hit rate frees a large share of the render pool and shortens the tail.

It is built after Phase 12 because the pool made "how much render work is there?" a measurable number. It is also the first phase where a **performance feature could become a security hole**: caching is the classic place where an access check gets skipped. Half of this note is about not doing that.

---

## 3. New concepts introduced

### 3.1 Cache-aside

**What it is:** the application, not the cache, orchestrates: look in the cache; on a miss, do the real work and put the result in the cache.
**The analogy:** a chef keeping a few finished plates under a heat lamp. Orders for the same dish go out instantly; otherwise the chef cooks from scratch and puts a spare under the lamp.
**Why here:** the renderer knows nothing about caching; `SecureTileService` asks the cache after authorisation and `AsyncTileService` stores the result after a render.
**How it works:** `prepareTile` → checks → `cacheLookup` → hit: return bytes / miss: fetch + render → `cacheRendered`.
**In our code:** `SecureTileService.java:147-160` (lookup) and `AsyncTileService.java:47-57` (fill).
**What breaks without it:** nothing functionally — it's an optimisation — which is exactly why a bug here is dangerous: it must *degrade to a miss*, never to an error. Both cache calls catch `RuntimeException` (`SecureTileService.java:164-180`).

### 3.2 Key design and versioned keys

**What it is:** the key is `sha256(userId | sessionId | documentVersionId | pageNumber | variant | rendererId | watermarkVersion)` (`TileCacheKey.java`).
**The analogy:** a coat-check ticket that encodes *whose* coat, *which* coat and *which cloakroom rules*. Change the rules and every old ticket is void without anyone walking the racks.
**Why we needed it:** the bytes depend on every one of those inputs. Leave one out and you serve the wrong watermark (a leak) or a stale render.
**How it works:** invalidation by **changing the key**. Bump `tilecache.watermark-version` (or swap the renderer) and every existing entry becomes unreachable and ages out. This is far simpler than finding and deleting entries.
**What breaks without it:** omit `userId` → buyer B receives buyer A's watermark (a forensic disaster: the leaked copy points at the wrong person). Omit `watermarkVersion` → a redesigned watermark keeps serving the old design for 15 minutes.

### 3.3 "Two hard things in computer science": invalidation

We use three complementary tools (D5), not one clever one:
1. **TTL (15 min)** — everything eventually disappears.
2. **Key change** — session, version, renderer, watermark version.
3. **Explicit `evictUser`** — on refund (`RefundService.java:144`) and suspension (`AdminUserService.java:124`). Best-effort by design (`TileCacheEvictor`), because…

### 3.4 Security ordering: authorise before you look up

**The classic bug:** "the cache is fast, so check it first." Then a revoked buyer, a suspended user or an expired session gets served from cache — the cache has quietly become a second, unguarded door.
**What we did:** the cache lookup sits at the *end* of `prepareTile`, after the signature, single-use, session, entitlement and page-range checks. Because of that, eviction on revocation is only an optimisation, not the security control — which is why it is allowed to fail silently. `TileCacheIT#afterRevocation_nextRequestIs403_eventhoughEntryIsCached` proves it: it revokes the entitlement *without* evicting and still gets 403.
A hit also still writes the access-log row (VIEW-13: a view is a view) and the rate limit still applies (it is a filter that runs before the controller).

### 3.5 Making the watermark cache-stable (D1)

The old label had a minute-level timestamp, so two renders of the same page a minute apart differ → a cache keyed on the inputs could never be reused *and* the bytes wouldn't be identical. New label: `{email} · #{userId} · {yyyy-MM-dd} UTC · s{sessionId}` (`SecureTileService.java:200`).
**Forensic trade-off:** we lost minute precision *inside the picture*. Acceptable because a leaked image still names the buyer, the day and the exact session, and `viewer_access_logs` holds the exact timestamp of every page view joined on the session id. Precision moved from the image to the log; identification did not weaken.

### 3.6 Disk vs Redis vs in-heap — with numbers

`requirements.md`: 5,000 users × 10 pages × ~500 KB ≈ **25 GB** (10–50 GB range).
| Store | Fits 25 GB? | Cost | Shared between servers? |
|---|---|---|---|
| In JVM heap | No — the server has 12 GB *total* and GC would suffer | RAM | No |
| Redis | Only with a large paid instance; our box shares 12 GB with Postgres, MinIO, JVM | RAM at ~$$/GB, also network copy of 500 KB per hit | Yes |
| **Local disk (default)** | Yes, capped at 2 GB by config | Cheap; an SSD read of 500 KB ≈ 1 ms | No |
Disk wins on a single server: the cache is bounded by *disk*, not RAM, and only a small index (a few hundred bytes per entry) is in heap. Redis is provided for scale-out, where "instance A rendered it, instance B can serve it" matters more than RAM cost. A 2 GB cap holds ~4,000 tiles at 500 KB — a working set of *active* readers, not all users; TTL and size bound do the rest.

### 3.7 Atomic file writes

`DiskTileCache.put` writes `<name>.part`, then `Files.move(..., ATOMIC_MOVE)` to `<name>.png` (`DiskTileCache.java:105-108`). A rename within one filesystem is atomic: a reader sees the whole old file or the whole new file, never half a PNG; a crash leaves a `.part` file which the startup wipe removes. **Also**: every put uses a *unique* file name, so two threads storing the same key never write the same file; the index keeps the last one, the removal listener deletes the loser.

### 3.8 Hit-rate economics

Expected saving per request ≈ `hitRate × (storageFetch + render)`, at a cost of `(1 − hitRate) × writeCost + lookupCost` on misses. With ~85 ms render, 40 % hit rate and ~1 ms disk read, the average tile falls from ~85 + fetch to ~51 + fetch ms of CPU-bound work, and the pool sees 40 % less load. If the hit rate were 5 % the write cost on the other 95 % could outweigh the gain — which is why the Grafana panel ("Tile cache hit rate") exists: measure, don't assume.

---

## 4. Best practices applied

| Practice | What we did | Why it matters | Where |
|---|---|---|---|
| Authorise first | Lookup after every check | A cache must not be a bypass | `SecureTileService.java:147` |
| Fail to a miss | Catch cache errors on read/write | Optimisation must not cause outages | `SecureTileService.java:164` |
| Strategy pattern | `WatermarkedTileCache` with Disk/Redis/Noop | Swap by config, test with Noop | `cache/WatermarkedTileCache.java` |
| Key includes every input | 7 fields hashed | Correct by construction | `cache/TileCacheKey.java` |
| Unknown config fails fast | `tilecache.type=typo` → startup error | No silent "cache off" | `cache/TileCacheConfig.java` |
| `no-store` to the browser | unchanged headers | Cache is server-side only (D6) | `SecureTileController.java:57,73` |
| Metrics for a new component | hit/miss/size/evictions + panels | Measure before believing | `ViewerMetrics.java`, dashboard panels 11–12 |

---

## 5. What does what — file map

| File | Responsibility |
|---|---|
| `viewer/cache/TileCacheKey.java` | The 7-field key and its sha256 |
| `viewer/cache/WatermarkedTileCache.java` | Strategy interface: `get`, `put`, `evictUser` |
| `viewer/cache/DiskTileCache.java` | Files + Caffeine index (TTL, size bound, delete-on-evict) |
| `viewer/cache/RedisTileCache.java` | Binary values, `EX ttl`, per-user index set |
| `viewer/cache/NoopTileCache.java` | `tilecache.type=none` |
| `viewer/cache/TileCacheConfig.java` / `TileCacheProperties.java` | Chooses the implementation from `tilecache.*` |
| `viewer/cache/TileCacheEvictor.java` | Best-effort `evictUser` for refunds/suspension |
| `viewer/service/SecureTileService.java` | Lookup after the checks; D1 watermark label |
| `viewer/service/AsyncTileService.java` | Hit → skip render pool, still log; miss → render, log, store |

**Request trace — a repeat view of page 3:**
1. `SecureTileController.getTile` (sets `no-store`) →
2. `AsyncTileService.getTile` → `SecureTileService.prepareTile`: signature, single-use, session, entitlement, page range all pass →
3. `tileCache.get(key)` **hits** → no storage fetch →
4. `recordAccess` writes the access-log row on a virtual thread →
5. bytes returned with `Cache-Control: no-store, private`.

---

## 6. Design decisions and trade-offs

### Decision: default to a disk cache
- **Alternatives considered:** Redis; in-heap (Caffeine only); CDN.
- **Why:** the 25 GB maths (§3.6); a single server; simplicity.
- **Gave up:** sharing across instances; a restart empties the cache (deliberately — see gotchas).
- **Revisit when:** running more than one backend instance → `tilecache.type=redis`.

### Decision: date + session precision in the watermark
- **Alternatives:** keep the minute and key the cache by minute (hit rate collapses); render once and stamp the time on the client (violates "browser never receives clean").
- **Gave up:** in-image minute precision. **Revisit when:** legal/forensics requires it in-image.

### Decision: eviction is best-effort
- **Alternative:** make eviction transactional with the refund. **Why not:** the check-before-lookup ordering already provides the guarantee; coupling a refund to Redis availability would trade a security property we already have for an availability risk we don't need.

### Decision: Caffeine's eviction policy, not strict FIFO
Caffeine uses W-TinyLFU (recency + frequency), so under size pressure the "oldest" is not guaranteed to be the first to go. For a tile cache this is a feature (frequently re-read pages stay); the test asserts the invariant that matters — bound respected and every evicted file deleted.

---

## 7. Interview questions

### Beginner
**Q: What did you cache, and why not the clean tile?**
A: The already-watermarked bytes, per buyer and session. A clean tile is the one thing the browser must never receive, so it is never cached anywhere reachable, and a shared clean copy would need a per-request render anyway — which is the cost we're avoiding.

**Q: What is cache-aside?**
A: The application checks the cache, and on a miss loads from the source and populates it. The cache itself is dumb.

**Q: What's a TTL?**
A: Time-to-live — how long an entry is valid. Ours is 15 minutes so stale or revoked data can't linger long.

### Intermediate
**Q: Where in the request does the cache lookup happen and why?**
A: After all authorisation checks, never before. A cache in front of the checks becomes a way to bypass them: a refunded buyer would still be served. We prove it with a test that revokes without evicting and still gets a 403.

**Q: How do you invalidate?**
A: Three layers: TTL; keys that change when the session, version, renderer or watermark version changes; and best-effort explicit eviction on refund/suspension. Because authorisation runs first, eviction is hygiene, not the safety net.

**Q: Why disk by default rather than Redis?**
A: Size. 5,000 users × 10 pages × 500 KB is ~25 GB; the server has 12 GB total. Disk is cheap and a local read is ~1 ms. Redis is there for multiple instances.

**Q: How do you write a cache file safely?**
A: Write to a temp file, then atomic rename. Readers see all or nothing; a crash leaves only a temp file that startup deletes.

### Advanced / follow-up probes
**Q: Two threads put the same key at once. What happens?**
A: Each writes its own uniquely named file and atomically renames it. The index keeps the last `put`; the Caffeine removal listener deletes the replaced file. A reader that loses the race sees a missing file, retries the lookup a couple of times (a miss is still legal under constant replacement; corruption never is), and finds the new entry. There's a parallel test with 16 threads.

**Q: Why not cache across users to save more?**
A: The watermark is per buyer by definition. Sharing would mean one buyer's identity on another's copy and would break traceability.

**Q: What did losing the minute in the watermark cost, and can you defend it?**
A: In-image precision. The image still has email, user id, date and session id; the access log has the exact time for each page, joined on session id. Identification is intact.

**Q: How would a hot-key stampede look and what would you do?**
A: Many concurrent misses for one key each render. It's bounded here because a key is one buyer's one page in one session (one reader), so concurrency per key is ~1. For shared keys I'd use single-flight (`Cache.get(key, loader)`).

### "Tell me about a bug you fixed"
**Q:** A concurrency bug in the disk cache.
A: My first `get` handled a vanished file by calling `invalidate(key)`. Under a parallel put test, a reader found the old entry, another thread replaced it and deleted the old file, the reader hit "file not found" and then invalidated the key — deleting the *new* entry. The parallel test failed with an empty result. Fix: remove only the exact entry I read (`remove(key, entry)`) and retry the lookup once. Lesson: invalidation by key is a race whenever the key can be re-populated; invalidate by identity.

---

## 8. Gotchas and bugs we hit

| Symptom | Root cause | Fix | Lesson |
|---|---|---|---|
| Parallel put test: `get` returned empty | `invalidate(key)` on file-not-found removed a fresh replacement | `remove(key, entry)` + bounded retry (`DiskTileCache.java:81-96`) | Invalidate by identity, not by key |
| Size-bound test: "newest survives" failed | Caffeine is W-TinyLFU, not FIFO/LRU | Assert the bound and file deletion, not which entry goes | Know your library's eviction policy |
| Cache tests hit each other's entries | `TRUNCATE ... RESTART IDENTITY` recreates the same user/session ids → same keys, and the cache dir outlives a test | Evict users 1..5 in `@BeforeEach` | Test isolation extends to every store, not just the DB |
| Second fetch of a page → 403 | Signed URL = HMAC(session\|page\|user\|exp) with exp in **whole seconds**; the same page minted twice in one second has the same signature, and signatures are single-use | Tests wait 1.1 s between mints. Pre-existing behaviour (real readers don't re-request one page within a second); noted as a follow-up | Second-resolution tokens have collisions |
| Listener deleted a newly written file | Naming files by key means "replace" and "delete old" target the same path | Unique file name per put | Never let old and new entries share a path |
| Stale files after restart | The index is in memory; files from a previous run are orphans, possibly of a since-revoked buyer | Startup wipe of `*.png`/`*.part` | A cache directory is not durable state |

---

## 9. New vocabulary

| Term | One-line meaning |
|---|---|
| Cache-aside | App checks cache, falls back to source, then fills the cache |
| Cache key / versioned key | Hash of every input; bumping a version field orphans old entries |
| TTL | How long an entry is valid |
| W-TinyLFU | Caffeine's eviction policy: recency + frequency, with an admission filter |
| Atomic rename | Filesystem move that readers see as all-or-nothing |
| Hit rate | hits / (hits + misses) |
| Cache stampede | Many concurrent misses for one key all doing the expensive work |
| Best-effort | May fail without failing the operation that triggered it |

---

## 10. If I had to defend this in a code review

- **Strongest:** authorisation runs before the lookup and a test proves it with a live cache entry; correctness never depends on eviction.
- **Strong:** the key contains every input, so invalidation is a version bump, not a hunt.
- **Weakest:** the disk cache is per-instance and its hit rate is unmeasured under load (the k6 run couldn't be done in CI). I would run it and tune TTL/size from real numbers. Second weakest: tile bytes at rest on disk are unencrypted watermarked images of one buyer; if that matters, put the directory on an encrypted volume or a tmpfs sized to the cap.
