# Perf 01 — Purchase-time watermarking + a faster PNG encoder (for a 0.1-CPU host)

> **Status:** Done
> **Built:** 2026-10-05
> **Requirement IDs covered:** VIEW-03 (server-side watermark, now applied at purchase time as well as live), VIEW-13 (every view still logged). Driven by the decision to host the 5-user beta on Render's free tier.
> **Commits:** _(see the "Purchase-time watermarking" PR)_

---

## 1. What we built, in plain English

SecureLeaf burns a watermark carrying the buyer's identity into every page a buyer reads. Until now that happened **on every page view**: fetch the clean page image, decode it, draw the watermark, encode it again, send it.

We tested the backend under the limits of Render's free tier (512 MB of memory, **one tenth of a CPU core**), with 5 people reading at once. A page took about **42 seconds**, and 6 of 11 page requests timed out (§8). Per-view watermarking does not work on a computer that slow.

So we moved the expensive work from **read time to purchase time**:

- When someone buys a book, a background job watermarks **every page of it for that buyer, once**, and stores the results.
- A page view now just fetches that buyer's ready-made page from storage. There is no image work at view time.
- If a page is not ready yet (a purchase made a few seconds ago), the view falls back to the old live watermarking, so nothing ever breaks.

We also made every PNG encode in the app about **40% cheaper** with three measured changes (§3.4).

**Before:** every page view = decode + watermark + encode (~263 ms of CPU, ~2.6 s on 0.1 CPU before any queueing).
**After:** every page view = one storage fetch; the watermarking happens once per buyer, in the background.

### Measured result (same test both times: 512 MB, 0.1 CPU, 5 k6 readers, 4 minutes)

| | Before (per-view watermark) | After (purchase-time) |
|---|---|---|
| Page time, median | 41.7 s | **5.0 s** |
| Page time, p95 | 53.1 s | **20.0 s** |
| Page requests OK | 5 / 11 (45%) | **28 / 29 (96%)** |
| Requests completed | 49 | **88** |
| Async timeouts | 6 | **0** |
| Sessions lost | 5 | 2 |
| Peak memory | 426 MB | 483 MB |
| Generating 5 buyers' pages (120) | — | 815 s, in the background |

The "after" run was on a busier laptop (its startup took 1146 s vs 843 s), so the improvement is if
anything understated. The remaining ~5 s is not image work any more: even GraphQL calls had a ~3.8 s
median. It is the fixed cost of *any* request (security filters, GraphQL, queries, JSON) on a tenth
of a core, plus queueing behind the other readers and their heartbeats. In the real reader, the next
page is prefetched while the current one is read, which hides most of it for page-by-page reading.

A/B of the encoder alone (CPU time per page, the Render JVM flags, same machine, back to back):
upload-pipeline encode **280 → 178 ms (−36%)**, watermark **410 → 194 ms (−53%)**.

---

## 2. Why it matters

On a big server, per-view watermarking costs a fraction of a second, and the tile cache (Phase 13) hides repeats. On a 0.1-CPU host it is fatal: every page render, heartbeat and GraphQL call shares that tenth of a core, so **one** slow thing makes **everything** slow. In the measured run the heartbeats were delayed so much that 5 reading sessions expired.

This is also a textbook trade-off that interviewers love: **compute when writing vs. compute when reading.** Pre-computing at purchase time costs storage (one copy of the book per buyer) and a delay after purchase. In return, reads cost no CPU at all. For a reading app, reads outnumber purchases by hundreds to one.

---

## 3. New concepts introduced

### 3.1 Precompute at write time vs. compute at read time

**What it is:** Doing expensive work once, when data changes, instead of every time it is read.

**The analogy:** A restaurant can cook every dish to order (fresh, but slow at the lunch rush), or prepare sandwiches in the morning (instant at lunch, but they take fridge space, and a change to the recipe means remaking them).

**Why we needed it here:** Reads are far more frequent than purchases, and our CPU budget is tiny.

**How it works:** Write path: purchase, then the reconciler watermarks all pages for that buyer, then stores them. Read path: checks, then fetch the stored page.

**In our code:** the view path consults the stored page first, after every security check: `backend/src/main/java/com/secureleaf/viewer/service/SecureTileService.java:162`
```java
Optional<byte[]> prewatermarked = prewatermarkedPages.find(
        entitlement.getId(), documentVersion.getId(), request.pageNumber(), servedVariant);
if (prewatermarked.isPresent()) {
    return new PreparedTile(request, session, documentVersion, contentPage, null, prewatermarked.get(),
            null, cacheKey);
}
```

**What breaks without it:** On a slow host, every view queues behind every other view's render (§8).

**The costs we accepted:** storage, roughly one copy of each purchased book per buyer, both sizes (~350 KB per page per size); a delay before a new buyer's pages are ready (§6); and a watermark that no longer names the reading session (§3.5).

### 3.2 The reconciliation loop (level-triggered, not edge-triggered)

**What it is:** A background loop that repeatedly compares the **desired** state with the **actual** state and fixes the difference, instead of reacting to individual events.

**The analogy:** A thermostat does not count how many times someone opened the door. It just reads the temperature and heats when it is too cold. If it misses a door opening, it still ends up right.

**Why we needed it here:** Entitlements change in at least five places: a purchase (`FulfillmentService`), a free-update migration to a new version (`EntitlementMigrationService`), a refund (`RefundService`), the load-test seeder, and every existing buyer on the day this shipped. Hooking each one is fragile: the next person to add a sixth place forgets the hook. One loop that asks "which ACTIVE entitlements are missing pages?" covers all of them, including events we missed during a restart.

**In our code:** the work list is computed from the data itself, `BuyerPageRepository.java:28`:
```sql
SELECT e.id FROM entitlements e
JOIN document_versions dv ON dv.id = e.document_version_id
WHERE e.status = 'ACTIVE' AND e.id > :afterId
  AND dv.processed_at IS NOT NULL AND dv.retired_at IS NULL
  AND (SELECT COUNT(*) FROM buyer_pages bp ...) < (SELECT COUNT(*) FROM content_pages cp ...)
```
And the opposite direction, `findStale` (`BuyerPageRepository.java:48`), finds pages nobody may be served any more: refunded, moved to a new version, or an old watermark design.

**Idempotent and resumable:** running it twice generates nothing the second time; a crash halfway through just leaves fewer rows, and the next run does the rest. There is no progress table to corrupt. This is the same design as the Phase 16 variant backfill, and the same idea as a Kubernetes controller.

**What breaks without it:** With event hooks only, a crash between "purchase committed" and "watermark job queued" would leave that buyer without pages forever. With the loop, they get them on the next run.

### 3.3 CPU-bound work and virtual threads (the carrier-starvation trap)

**What it is:** Java 21 virtual threads are cheap threads that the JVM multiplexes onto a few real **carrier** threads, about one per CPU core. They are great for code that mostly *waits* (database, network). They are dangerous for code that mostly *computes*: a virtual thread that never waits occupies its carrier.

**Why we needed it here:** This app runs web requests **and** `@Scheduled` tasks on virtual threads (`spring.threads.virtual.enabled: true`). On a 0.1-CPU host the JVM sees one CPU, so there is **one carrier**. If the watermark loop ran on a scheduler (virtual) thread, it would starve every web request for minutes.

**In our code:** the timer only *starts* a run; the work happens on a dedicated **platform** thread, `BuyerPageReconciler.java:48`:
```java
private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
    Thread thread = new Thread(runnable, "prewatermark-1");
    thread.setDaemon(true);
    thread.setPriority(Thread.MIN_PRIORITY);
    return thread;
});
```
Same lesson as Phase 12's `tile-render-` pool. An `AtomicBoolean` makes sure only one run happens at a time (`start()`, line 74).

### 3.4 Profiling beats intuition: three measured encoder fixes

**What it is:** Measuring where time actually goes before optimising.

**What we expected:** "PNG compression is slow; switch to JPEG and it will be several times faster." **That was wrong.** On a real page tile, JPEG alone saved only ~25%.

**What we measured** (CPU time per watermarked 1239×1754 page, one thread):

| Configuration | Decode | Draw text | Encode | **Total** | Size |
|---|---|---|---|---|---|
| Before: `ImageIO.write`, disk cache on, `INT_RGB` | 63 ms | 8 ms | 192 ms | **263 ms** | 338 KB |
| Disk cache **off** | 42 ms | 6 ms | 167 ms | 215 ms | 338 KB |
| …and `3BYTE_BGR` pixel layout | 37 ms | 4 ms | 149 ms | 190 ms | 338 KB |
| …and zlib **level 2** (was 4) | 40 ms | 7 ms | 110 ms | **157 ms** | 349 KB |
| JPEG q85 (same fixes) | 38 ms | 3 ms | 94 ms | 134 ms | 298 KB |

Three findings:
1. **Drawing the watermark text costs 4–8 ms.** Encoding the image costs 100–200 ms. The "watermark" was never the expensive part.
2. **`ImageIO` writes every image through a temporary file by default** (`ImageIO.setUseCache(true)`). Our images are already in memory, so that file is pure waste: ~20% of the CPU. Fixed once, JVM-wide, in `content/image/ImageIoConfig.java:27`.
3. **The pixel layout matters.** `TYPE_INT_RGB` forces the encoder to convert every pixel; `TYPE_3BYTE_BGR` is what it consumes directly (`Java2DWatermarkRenderer.java:70`).

**Why PNG level 2 and not JPEG:** JPEG would save another ~15% of CPU but is **lossy**: it blurs the edges of text, and this product *is* text pages. PNG also keeps every contract unchanged (`image/png` everywhere, no frontend change). And with purchase-time watermarking the encode runs once per page per buyer, in the background, so the last 15% matters much less.

**A second correction, found by reading the JDK source:** the quality→level formula in my first draft comment was wrong. JDK 21's `PNGImageWriter` computes `level = 9 - round(9 * quality)` with a default of 4, so quality 0.8 is level 2, not level 1. We benchmarked level 1 too: same CPU within noise, bigger files. Level 2 stays (`PngEncoder.java:34`). **Lesson: check claims you put in comments against the source.**

### 3.5 The watermark label changes, and the forensic trade-off

**Live watermark (still used for pages not generated yet):** `email · #userId · date UTC · s{sessionId}` (Phase 13).
**Stored watermark:** `email · #userId · purchase-date UTC · e{entitlementId}` (`BuyerPageWatermarker.java:132`).

There is no reading session at purchase time, so the **entitlement id** replaces the session id. A leaked page still identifies **exactly one purchase** (entitlement → order → payment) and the buyer. What it no longer says on its face is *which sitting* the leak came from; the exact time of every page view is still in `viewer_access_logs`. This is a deliberate, documented weakening of the forensic detail on the page, in exchange for a usable product on free hosting.

### 3.6 Order of side effects: object first, row second

The watermarker **stores the image first and inserts the database row second** (`BuyerPageWatermarker.java:94-95`). If the process dies between the two, the worst case is an orphan file that the next run overwrites (the object key is deterministic, `objectKey`, line 143). The reverse order could leave a row pointing at a missing file, and the view path would then have to treat "row exists" as "maybe exists". A duplicate insert by a concurrent run hits the unique constraint and is treated as "already done" (line 165).

---

## 4. Best practices applied

| Practice | What we did | Why it matters | Where |
|---|---|---|---|
| Measure before optimising | Benchmarked 6 encoder configurations on a real tile; rejected the intuitive fix | The intuitive fix (JPEG) was the smaller win | `PngEncoder.java` comment |
| Verify against the source | Read `PNGImageWriter` to get the real quality→level formula | A wrong comment is worse than none | `PngEncoder.java:28-34` |
| Authorise before serving cached/precomputed data | Stored pages are looked up only after signature, single use, session, ACTIVE entitlement and page range | A precomputed copy must never become a bypass | `SecureTileService.java:162` |
| Graceful degradation | Missing or unreadable stored page means live watermark, never an error | The feature can be empty, half-done or broken without users noticing | `PrewatermarkedPages.java` |
| Reconciliation over event hooks | One loop covers purchases, migrations, refunds, seeding, backfill | No "forgot the hook" bugs; heals after crashes | `BuyerPageReconciler.java` |
| Bulkhead CPU work off virtual threads | Dedicated platform thread | One carrier on 0.1 CPU; starving it starves the site | `BuyerPageReconciler.java:48` |
| Idempotency via unique constraint | `uq_buyer_pages_identity`; duplicates are "already done" | Two runs or two instances can't double-insert | `V14__buyer_pages.sql` |
| Versioned derived data | `watermark_version` in the key and the path | Changing the watermark design is a config bump; old pages are swept | `application.yml` `drm.prewatermark.version` |
| Index foreign keys | `idx_buyer_pages_document_version_id` | Postgres doesn't index FK columns; cascades would scan the table | `V14__buyer_pages.sql` |
| Every view still logged | Stored-page hits reuse the cache-hit path that writes the access log | VIEW-13 holds whatever path served the bytes | `AsyncTileService.java` |

---

## 5. What does what — file map

| File | Responsibility |
|---|---|
| `db/migration/V14__buyer_pages.sql` | Table of stored per-buyer pages |
| `viewer/entity/BuyerPage.java`, `viewer/repository/BuyerPageRepository.java` | Row + the "missing" and "stale" queries |
| `viewer/prewatermark/BuyerPageReconciler.java` | One run: delete stale, generate missing, on its own platform thread |
| `viewer/prewatermark/BuyerPageReconcilerJob.java` | The timer (every 15 s; off in tests) |
| `viewer/prewatermark/BuyerPageWatermarker.java` | One entitlement: fetch clean, watermark, store, insert row |
| `viewer/prewatermark/PrewatermarkedPages.java` | View-path lookup; never fails a request |
| `viewer/service/SecureTileService.java` | Uses the stored page after all checks, before the tile cache |
| `content/image/PngEncoder.java`, `content/image/ImageIoConfig.java` | Faster PNG encoding; ImageIO disk cache off |
| `content/watermark/Java2DWatermarkRenderer.java` | Draws onto `3BYTE_BGR`, encodes through `PngEncoder` |
| `viewer/metrics/ViewerMetrics.java` | `secureleaf.prewatermark.{hit,miss,generated}` |

**Request trace — a buyer opens page 7:**
1. `viewerPageUrl` signs a single-use URL (unchanged) →
2. `GET /api/viewer/tiles/{session}/7` → `RateLimitFilter` → `SecureTileService.prepareTile`: signature, single use, active session, ACTIVE entitlement, page range (all unchanged) →
3. `PrewatermarkedPages.find(entitlement, version, 7, variant)` → row found → `storage.get` →
4. `AsyncTileService` sees "already watermarked bytes" → writes the access-log row → returns the PNG. **No render.**
5. (Row not found → steps continue exactly as before: tile cache → clean fetch → render pool.)

---

## 6. Design decisions and trade-offs

### Decision: precompute at purchase time instead of optimising the per-view render
- **Alternatives considered:** (a) keep per-view watermarking and only make encoding faster; (b) a bigger tile cache; (c) serve the smaller MOBILE size by default; (d) pay for a bigger server.
- **Why we chose this:** (a) gives ~40% (needed, done anyway, §3.4), not the ~20× we needed. (b) The tile cache helps only *repeat* views within a session (~5% hit rate in Phase 17's mix). (c) Halves the work but degrades quality for everyone. (d) Breaks the ₹0 constraint.
- **What we gave up:** storage per buyer; session id on the page; a delay before a new buyer's pages exist (they are served live meanwhile).
- **When we would revisit:** many buyers per book, or very long books, making per-buyer storage expensive. Then: watermark only a sliding window of pages, or a faster native encoder (libvips, the parked Phase 14).

### Decision: one reconciliation loop instead of hooks in FulfillmentService etc.
- **Alternatives considered:** an `@TransactionalEventListener(AFTER_COMMIT)` on purchase that queues a job; a `watermark_jobs` table with `SKIP LOCKED` workers (like Phase 2's pipeline).
- **Why we chose this:** five entry points today, more tomorrow; the loop derives work from the data and needs no new job table. Restart-safe by construction, which matters on a host that restarts and sleeps.
- **What we gave up:** latency (up to one interval, 15 s, before generation starts) and one cheap query every 15 s even when idle.
- **When we would revisit:** if 15 s matters, add an after-commit "nudge" that calls `start()` right away; the loop stays the source of truth.

### Decision: PNG level 2, not JPEG
See §3.4: lossless text, unchanged contracts, and the remaining 15% runs in the background now.

---

## 7. Interview questions

### Beginner
**Q: Why move watermarking to purchase time?**
A: Because reads vastly outnumber purchases. Doing the expensive image work once per buyer, in the background, means every page view is just a file fetch. On our tiny free server, per-view watermarking made pages take about 40 seconds with five readers.

**Q: What happens if someone opens a book seconds after buying it?**
A: The pages aren't generated yet, so the view falls back to watermarking live, the way it always did. It's slower for that first read, but it never fails. A few seconds later, the background job has stored their copy.

**Q: Isn't a stored, already-watermarked page a security risk?**
A: The stored copy is only looked up after every check passes: signed URL, single use, active session, active entitlement, page range. It's also *that buyer's* copy, carrying their identity. And browsers still never get a clean page.

### Intermediate
**Q: Why a reconciliation loop instead of firing a job when the purchase happens?**
A: Entitlements change in several places: purchase, refund, version migration, seeding, and every existing buyer on launch day. A loop that compares desired and actual state covers all of them and heals after a crash. With hooks, one missed event means a buyer never gets pages.

**Q: How do refunds work with stored pages?**
A: The view path already refuses a revoked entitlement before touching storage. Separately, the reconciler's cleanup finds pages whose entitlement isn't ACTIVE, or whose version no longer matches, and deletes both the file and the row.

**Q: You expected JPEG to be the big win. What happened?**
A: I measured. Drawing the text took 4–8 ms; encoding took 100–200 ms. Turning off ImageIO's temp-file cache and using the pixel layout the encoder wants gave more than switching format. I chose lossless PNG at a faster compression level: about 40% less CPU and no contract changes.

### Advanced / follow-up probes
**Q: Why not run the reconciler on the scheduler thread?**
A: Our scheduled tasks and requests run on virtual threads, which share one carrier thread per CPU core. On a 0.1-CPU box that's one carrier, so a CPU-bound loop on a virtual thread would block every web request. It gets its own platform thread.

**Q: Two instances run the reconciler at once. What happens?**
A: Both may render the same page. Both PUT the same deterministic key, which is harmless. The second insert hits the unique constraint and is treated as "already done". It wastes CPU but is correct. With more than one instance I'd add a Postgres advisory lock so only one runs at a time.

**Q: How would you change the watermark design later?**
A: Bump `drm.prewatermark.version`. Every existing row becomes stale. The cleanup deletes old rows and files, and the generator rebuilds with the new design. Meanwhile views fall back to live watermarking. The version is also in the object path, so new files never overwrite files that are about to be deleted.

**Q: What did you give up forensically?**
A: The stored page names the buyer and the purchase (entitlement id), not the reading session. You can still trace a leak to the buyer and the order, but not to the exact sitting from the page alone. The access log still has every view's time and IP.

### "Tell me about a bug you fixed"
**Q: Tell me about a performance problem you diagnosed.**
A: Our backend had to fit Render's free tier: 512 MB and a tenth of a CPU. I reproduced those limits in Docker and ran five simulated readers. Pages took 42 seconds, and half the requests timed out. The logs showed `AsyncRequestTimeoutException`, and even a Redis connection timing out, so the root cause was CPU starvation from rendering on every view. I moved watermarking to purchase time with a reconciliation loop. Profiling the encoder also showed my JPEG assumption was wrong; the real wins were ImageIO's temp-file cache and the pixel layout. The lesson: reproduce the real constraints, then measure, before choosing a fix.

---

## 8. Gotchas and bugs we hit

| Symptom | Root cause | Fix | Lesson |
|---|---|---|---|
| **Container killed at 512 MB** (`oomKilled=true`, exit 137) while processing page 3 of 12 | Default `-XX:MaxRAMPercentage=70` gave the heap 358 MB; metaspace, code cache, thread stacks and Java2D buffers live *outside* the heap and pushed the total over 512 MB | Explicit budget: `-Xmx256m -XX:MaxMetaspaceSize=180m -XX:ReservedCodeCacheSize=48m -Xss512k`, SerialGC | **The heap is not the process.** Size the whole JVM against the container limit |
| **Startup took 25 minutes** on 0.1 CPU | The C2 optimising JIT compiler burned the tiny CPU budget | `-XX:TieredStopAtLevel=1` (C1 only): **14 min** | On a starved CPU, JIT compilation competes with your app |
| **42 s per page, 6/11 requests failed** with 5 readers | Every view rendered on the same 0.1 CPU; requests exceeded Spring's async timeout (`AsyncRequestTimeoutException`); heartbeats lagged so sessions expired | Purchase-time watermarking (this note) | **A queue on a starved CPU makes everything slow, not just the slow thing** |
| After the change, book processing in the 512 MB run took 415 s vs 191 s before: a regression? | **No.** The whole run was on a busier host (startup +35% with no startup change); a `--cpus=0.1` container only gets its slice when the host has one. A back-to-back A/B of the encoders measured the new one **36% faster** | Kept the change; recorded the A/B | **When an end-to-end number moves, isolate the variable before blaming the code** (and measure CPU time, which a cap doesn't distort) |
| Suspected `TieredStopAtLevel=1` (C1 only) slowed image work 4× | A/B under both JIT settings: 279 vs 290 ms, no difference | No change | A plausible theory is not a measurement |
| JPEG expected "several times faster"; measured 25% | Encoding cost was dominated by ImageIO's temp-file cache and pixel conversion, not the format | Cache off + `3BYTE_BGR` + PNG level 2 | **Profile before choosing a fix** |
| Comment claimed quality 0.8 = zlib level 1 | Wrote it from memory | Read `PNGImageWriter`: `9 - round(9q)`, so level 2 | **Verify claims you write into comments** |
| *(test harness)* `AccessDeniedException: /app/C:` | Git Bash on Windows rewrote `/tmp/users.csv` into a Windows path inside `docker run -e` | `MSYS_NO_PATHCONV=1` | Windows shells mangle Unix paths passed to Docker |
| *(test harness)* single-size option sent an empty argument | PowerShell unwraps a one-item array returned from a `switch`; `$list[0]` on a hashtable is `$null`; PS 5.1 drops empty native args, shifting every option | Wrap in `@( )`; a guard that refuses an incomplete size | **Test the real code path, not a parallel one** (the dry run used `foreach`, the loop used `[0]`) |
| `ViewerIT` rate-limit tests fail (429 expected) on this laptop | **Pre-existing**: they assume requests finish faster than the bucket refills; reproduced on clean `HEAD` in a separate worktree | Not changed here | **Prove causation with a baseline before "fixing"** |

---

## 9. New vocabulary

| Term | One-line meaning |
|---|---|
| **Precompute at write time** | Do expensive work once when data changes, so reads are cheap |
| **Reconciliation loop** | A loop that repeatedly drives actual state towards desired state, instead of reacting to events |
| **Level-triggered vs edge-triggered** | Acting on the current state (level) vs on change events (edges); level-triggered heals missed events |
| **Carrier thread** | A real OS thread that runs virtual threads; CPU-bound virtual threads monopolise it |
| **zlib compression level** | 0 (none) to 9 (smallest, slowest); PNG uses it; level 2 trades ~3% size for speed |
| **`TieredStopAtLevel=1`** | Run only Java's quick C1 compiler; less peak speed, far less CPU spent compiling |
| **Off-heap memory** | JVM memory outside the heap (metaspace, code cache, stacks, native buffers) that a container limit still counts |

---

## 10. If I had to defend this in a code review

- **Strongest point:** page views no longer do image work at all, and the design degrades gracefully: missing, stale or unreadable stored pages fall back to the old path, so the feature can be empty or half-done without users noticing.
- **Also strong:** every number in this note was measured on the real code under the real limits (512 MB / 0.1 CPU), including the decisions that went *against* my first guess (JPEG, zlib level).
- **Honest limitation:** storage grows with buyers × pages × sizes. Fine for 5 users and the 10 GB free tier; wasteful for a book with 10,000 buyers.
- **Weakest point, fix first:** startup is still ~14 minutes on 0.1 CPU, so every deploy or restart is an outage of that length. Next steps: AppCDS (class-data sharing) and Spring AOT, measured the same way.
