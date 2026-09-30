# Phase 12 — Decoupled rendering pool + backpressure

> **Status:** Done (perf before/after run deferred to the owner — see §8 and `docs/perf/phase-12-render-pool.md`)
> **Built:** 2026-09-30
> **Requirement IDs covered:** MVP2-03. Spec: `docs/phases/phase-12-render-pool-backpressure.md`.
> **Commits:** see the `auto/issue-12` branch (`Phase 12: …`)

---

## 1. What we built, in plain English

Every page a buyer reads is a picture the server draws on the spot: it fetches the clean page, then
paints the buyer's name across it (the watermark). Painting is heavy computer work. Until now that
painting happened on the same small crew of workers (Tomcat's request threads) that also answer
login, "is my session alive?" heartbeats and every other click. If a hundred readers turned pages at
once, the painting used up the whole crew and *everything* — even logging in — waited in line.

Now painting has its **own separate crew**, sized to the number of CPU cores. It has a **small
waiting room** (200 seats). If the room is full, the next reader is told immediately "we're busy,
try again in a second" (HTTP **503** with `Retry-After: 1`) instead of standing in an ever-longer
line. If one page takes longer than 5 seconds, it is abandoned with the same 503. The reader's
browser quietly retries (at most twice, with a random delay) and shows "Busy, retrying…".
Everything that is *waiting* rather than *computing* (talking to the database, to storage, to
Redis) now runs on Java 21 **virtual threads**, which are cheap enough that waiting no longer ties
up a scarce worker.

**Before this phase:** one shared pool; slow renders starve unrelated requests; overload = slow collapse.
**After this phase:** rendering is isolated (a *bulkhead*), bounded, timed out, observable, and fails fast.

---

## 2. Why it matters

Phase 10 showed that almost all server time per tile is the Java2D render. Phase 11 limited *one
buyer's* speed, but a crowd of honest buyers can still overload the server. Without this phase the
failure mode of overload is the worst one: latency for everyone climbs together until timeouts
cascade. With it, overload degrades **one** feature (page images) while login, browsing and
heartbeats stay fast — and the system says so honestly instead of hanging.

---

## 3. New concepts introduced

### 3.1 Bulkhead pattern

**What it is:** giving each kind of work its own limited pool of resources so a failure or overload in one cannot drain the others.
**The analogy:** a ship's hull is split into watertight compartments; a hole floods one compartment, not the ship.
**Why we needed it here:** rendering (CPU-heavy, slow) and request handling (light, must stay snappy) shared threads.
**How it works:** a dedicated `ThreadPoolExecutor` only for the watermark; the rest of the app never touches it.
**In our code:** `RenderPool.java:35` (the pool), `AsyncTileService.java:45` (only the render is submitted to it).
```java
// backend/.../viewer/render/RenderPool.java:49
this.executor = new ThreadPoolExecutor(size, size, 0L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(properties.pool().queueCapacity()), ..., new ThreadPoolExecutor.AbortPolicy());
```
**What breaks without it:** a render backlog occupies all 200 Tomcat threads; GraphQL, login and heartbeats queue behind page images. (Acceptance test 4 proves the bulkhead: a heartbeat during saturation stays under 200 ms.)

### 3.2 CPU-bound vs I/O-bound work

**What it is:** CPU-bound work keeps a core busy computing (drawing text on an image); I/O-bound work mostly *waits* (a database reply, a file from MinIO).
**The analogy:** a chef chopping vegetables (CPU: you need a chef per pair of hands) versus a chef waiting for the oven (I/O: one chef can watch many ovens).
**Why it matters here:** the right thread strategy is opposite for the two. CPU work: about one thread per core — more just adds context switching. I/O work: as many threads as there are waiting requests.

### 3.3 Virtual threads — and what they do *not* fix

**What it is:** Java 21 threads managed by the JVM, not the OS. Millions are cheap. When a virtual thread blocks on I/O the JVM parks it and reuses the underlying OS ("carrier") thread for something else.
**Why we used them:** `spring.threads.virtual.enabled=true` (`application.yml`) makes Tomcat run each request on a virtual thread, so request handling, storage and DB waits don't pin scarce platform threads. The access-log write also hops onto a virtual thread (`AsyncTileService.java`).
**The interview trap:** *"Why not just run the watermark on virtual threads too?"* Because virtual threads help when threads **wait**. Java2D never waits; it burns a core. A virtual thread doing CPU work simply occupies a carrier thread (there are only as many carriers as cores), and you lose the bound on parallelism that protects the box. So the render pool is deliberately **platform** threads.
**Pinning:** a virtual thread that blocks *inside a `synchronized` block* (Java 21) can't unmount, so it holds its carrier ("pinned"). We grepped the code: the only `synchronized` in the tile/GraphQL path is `RateLimiter.proxyManager()`'s one-time lazy init; `MockRazorpayGateway` (dev only) synchronizes short methods. We did **not** run JFR (`jdk.VirtualThreadPinned`) in this automated run; the owner should during the perf run (see `docs/perf/phase-12-render-pool.md`).

### 3.4 Bounded queues — why unbounded queues are latency bombs

**What it is:** a queue with a maximum length. **The analogy:** a restaurant that takes reservations forever: at 8 p.m. you are told "your table is in 3 hours". A bounded waiting list says "full, come back later" on the spot.
**Why:** with an unbounded queue, overload never produces an error — it produces *latency*, growing without limit, until clients time out and retry, which adds more load. A bounded queue turns "slowly dead" into "quickly told no".
**Sizing (Little's Law, L = λ × W):** the number of tiles in the system equals arrival rate times time-in-system. With ~85 ms renders (Phase 10 baseline) on 2 cores, capacity is ≈ 2 / 0.085 ≈ 23 tiles/s. A queue of 200 at that rate is ≈ 8.5 s of waiting — longer than our 5 s timeout, so in practice the **timeout, not the queue, is the real limit**, and 200 is a generous ceiling rather than a tight one. If the queue is regularly non-empty, that is a signal to add capacity, not to enlarge the queue.

### 3.5 429 vs 503

**429 Too Many Requests** = *you* (this client) exceeded *your* allowance — Phase 11's per-buyer limiter. **503 Service Unavailable** = *we* are overloaded; you did nothing wrong. Why it matters: clients, proxies, monitoring and humans react differently. A 429 spike means abuse; a 503 spike means capacity. Both carry `Retry-After`. Our handler: `GlobalRestExceptionHandler.java:48`. The frontend also treats them differently: 429 retries once after exactly `Retry-After`; 503 retries up to twice with **jitter** (random 0.5–1.5× delay), so readers who were all shed at the same moment don't all come back at the same instant (a "thundering herd").

### 3.6 Timeouts and cancellation

**Timeout:** the request has a deadline: `render.timeout-ms` = 5000, counting queue wait + render. `CompletableFuture.orTimeout` completes the future exceptionally — but it does **not** stop the thread that is still working. So on failure we also call `Future.cancel(true)` (`RenderPool.java:95`), which interrupts the thread. Caveat (honest): real Java2D/ImageIO code is *not* interruptible mid-draw, so for a truly stuck render the interrupt only helps once the code next checks the flag; it does, however, remove a task that was still **queued**. The test stub is interruptible, so the test proves the wiring (active count returns to 0).

### 3.7 Splitting a request across threads (CompletableFuture controller)

`SecureTileController.getTile` returns `CompletableFuture<ResponseEntity<byte[]>>`. Spring MVC releases the request thread while the future is pending and *re-dispatches* the request when it completes (`DispatcherType.ASYNC`). Pipeline (`AsyncTileService`): (1) D6 checks + storage on the request thread → (2) watermark on `tile-render-N` → (3) access-log INSERT on a virtual thread. It is a separate bean because `@Transactional` on `prepareTile` works through Spring's proxy; a self-call would silently skip the transaction (a classic trap). As a bonus, the DB transaction now ends *before* the render, so a Postgres connection isn't held while a tile waits in the queue.

---

## 4. Best practices applied

| Practice | What we did | Why it matters | Where |
|---|---|---|---|
| Bulkhead | Dedicated render pool | Overload of one feature can't sink the rest | `RenderPool.java:35` |
| Bounded queue + fail fast | `ArrayBlockingQueue` + `AbortPolicy` → 503 | No latency bomb | `RenderPool.java:49` |
| Explicit deadline | `orTimeout` + `cancel(true)` | No unbounded waits, no leaked threads | `RenderPool.java:92` |
| Observability first | queue/active gauges, wait timer, rejected/timeout counters, Grafana panels | You can see saturation before users do | `ViewerMetrics.java`, `secureleaf-viewer.json` |
| Config not constants | `render.*` properties | Retune without redeploy | `RenderProperties.java` |
| Right status code | 503 not 429 | Signals server overload | `GlobalRestExceptionHandler.java:48` |
| Jittered client retry | `busyBackoffMs` | Avoids retry stampedes | `frontend/src/lib/rateLimit.ts` |

---

## 5. What does what — file map

| File | Responsibility |
|---|---|
| `viewer/render/RenderPool.java` | The bulkhead: platform threads `tile-render-N`, bounded queue, timeout, cancel, metrics |
| `viewer/render/RenderProperties.java` / `RenderConfig.java` | `render.pool.size` (default CPUs), `render.pool.queue-capacity` (200), `render.timeout-ms` (5000) |
| `viewer/render/RenderUnavailableException.java` | Queue full or timeout → mapped to 503 |
| `viewer/service/AsyncTileService.java` | Orchestrates prepare → render → log across threads; tile-request timer |
| `viewer/service/SecureTileService.java` | `prepareTile` (D6 checks + storage, transactional), `renderWatermark`, `recordAccess` |
| `viewer/controller/SecureTileController.java` | Returns `CompletableFuture`; sets `Cache-Control` early |
| `auth/security/SecurityConfig.java` | Permits the container's `ASYNC` re-dispatch |
| `viewer/metrics/ViewerMetrics.java` | `secureleaf.render.*` meters |
| `frontend/src/lib/rateLimit.ts`, `hooks/viewer/useSecureTile.ts`, `ReaderPage.tsx` | 503 retry + "Busy, retrying…" |
| `RenderBackpressureIT.java` | Acceptance tests 1–4 with pool 1 / queue 1 and a blocking renderer stub |

**Request trace — one tile:**
1. Rate-limit filter (Phase 11) runs first → 429 if over budget.
2. `SecureTileController.getTile` → `AsyncTileService.getTile` (virtual request thread).
3. `SecureTileService.prepareTile`: signature, single-use, session, entitlement, page range, storage fetch. Transaction ends.
4. `RenderPool.submit` → queue full? → 503 immediately. Otherwise a `tile-render-N` thread runs the watermark.
5. On success, a virtual thread writes the access log; the controller builds the PNG response (re-dispatched as ASYNC).
6. Timeout at any point after submit → 503 + `Retry-After: 1`, task cancelled.

---

## 6. Design decisions and trade-offs

### Decision: platform threads for rendering, virtual threads for I/O
- **Alternatives:** everything virtual; everything platform (status quo).
- **Why:** match thread type to the work (§3.2–3.3).
- **Gave up:** simplicity of one model; a second pool to size and monitor.
- **Revisit:** if the renderer becomes an out-of-process call (libvips, Phase 14) it turns into I/O and virtual threads fit.

### Decision: 503 + `Retry-After: 1` on saturation, rather than blocking the caller (`CallerRunsPolicy`) or a large queue
- **Alternatives:** `CallerRunsPolicy` (the request thread renders — defeats the bulkhead); unbounded queue (latency bomb); 429.
- **Why:** fail fast, protect the rest of the app, be honest about who is at fault.
- **Gave up:** some readers see "Busy" instead of a slow page under peak load.

### Decision: a separate `AsyncTileService` bean and `PreparedTile` record
- **Alternative:** one `@Transactional` method that submits work and waits — holds a DB connection and a thread while queued.
- **Gave up:** the entities are detached after `prepareTile`; the access-log write relies on them only as FK references (works because the needed associations were already touched inside the transaction).

### Decision: permit `DispatcherType.ASYNC` in Spring Security
- **Why:** the async re-dispatch has no JWT filter pass and an empty context in a stateless app, so it would 401 an already-authorized tile. Clients cannot originate an ASYNC dispatch; the first pass has already enforced every rule.

---

## 7. Interview questions

### Beginner
**Q: What is a thread pool and why not create a thread per task?**
A: A pool keeps a fixed set of already-created threads and hands them tasks. Creating OS threads is expensive and unbounded creation can exhaust memory; a pool caps the concurrency.

**Q: What does HTTP 503 mean and how does it differ from 429?**
A: 503 says the server can't handle the request right now — overload; 429 says this client sent too many. We return 503 with `Retry-After` when the render queue is full because the buyer did nothing wrong.

### Intermediate
**Q: What is the bulkhead pattern and where did you use it?**
A: Isolating resources per workload so one can't starve the others. I gave watermark rendering its own bounded pool, so a render backlog can't block login or heartbeats. I proved it with a test that saturates the pool and times a heartbeat, under 200 ms.

**Q: Why not use virtual threads for the watermark?**
A: Virtual threads make *waiting* cheap. Java2D is CPU-bound, it never waits, so a virtual thread would just hog a carrier thread and I'd lose the limit on parallel CPU work. Platform threads sized to the core count is right for CPU work.

**Q: Why is an unbounded queue dangerous?**
A: Overload turns into ever-growing latency rather than an error. Users wait, time out, retry, adding load. A bounded queue rejects early so the client can back off.

### Advanced / follow-up probes
**Q: How did you size the pool and queue?**
A: Pool = cores, since the work is CPU-bound. For the queue I used Little's Law: items in system = arrival rate × time in system. At about 85 ms per render on 2 cores the pool does ~23 tiles/s, so 200 queued tiles would wait ~8.5 s — beyond the 5 s timeout. So the timeout is the effective limit; the queue just needs to absorb short bursts.

**Q: A timeout fired but is the thread actually free?**
A: `orTimeout` alone only fails the future; the worker keeps running. I call `Future.cancel(true)` to interrupt it and test with an interruptible stub that the active-thread gauge returns to 0. Honest limit: uninterruptible native/Java2D code only stops when it returns.

**Q: What is virtual-thread pinning?**
A: On Java 21, a virtual thread blocking inside `synchronized` (or a native frame) can't unmount, so it holds its carrier thread. Too many pinned threads recreate the platform-thread shortage. Detect with JFR event `jdk.VirtualThreadPinned`; fix by using `ReentrantLock` instead.

**Q: Why did the tile endpoint start returning 401 after you made it async?**
A: See the bug table.

### "Tell me about a bug you fixed"
**Q:** After converting the controller to return a `CompletableFuture`, every tile test failed with 401. What happened?
A: The container re-dispatches an async request when the result is ready. In a stateless JWT app that second pass has no authentication, and Spring Security 6 authorizes all dispatcher types, so a request we'd already authorized was rejected. I allowed `DispatcherType.ASYNC` in the authorization rules, reasoning that only the container can produce that dispatch. Lesson: async changes the request lifecycle, security rules run again.

---

## 8. Gotchas and bugs we hit

| Symptom | Root cause | Fix | Lesson |
|---|---|---|---|
| Every tile 401 after going async | Async re-dispatch re-runs authorization with an empty context | `dispatcherTypeMatchers(ASYNC).permitAll()` | Async = second pass through the filter chain |
| `Cache-Control: no-store, private` came back as Spring Security's `no-cache, no-store, max-age=0, must-revalidate` | With async return the security header writer wrote first; `ResponseEntity` headers don't override existing ones | Set the header on `HttpServletResponse` before returning the future | Header order changes when the response is written later |
| Timeout did not free the worker | `orTimeout` fails the future only | `cancel(true)` on the submitted `Future` | Timeouts ≠ cancellation |
| `@Transactional` would be skipped | Self-invocation bypasses Spring's proxy | Orchestration lives in `AsyncTileService`, separate bean | Classic proxy trap |
| Existing `ViewerIT` tile calls broke | MockMvc reports "async started" until dispatched | Test helper `tile(...)` does `asyncDispatch` | Test tools must follow the async lifecycle |
| `LibraryIT` query-count test failed after enabling virtual threads | The test's SQL recorder identified request threads by the name `http-nio-`; virtual request threads are named `tomcat-handler-N` | Recorder accepts both prefixes | Changing the threading model breaks anything keyed on thread names |
| **Not done:** Phase 10 k6 smoke re-run and JFR pinning check | The unattended CI run had no MinIO and no time for a full stack | Procedure and empty before/after table in `docs/perf/phase-12-render-pool.md` | Never invent numbers |

---

## 9. New vocabulary

| Term | One-line meaning |
|---|---|
| Bulkhead | Separate resource pools per workload so failures don't spread |
| Backpressure | Telling upstream "slow down / I'm full" instead of buffering unboundedly |
| Load shedding | Rejecting some work on purpose to keep the rest healthy |
| Virtual thread | JVM-managed lightweight thread (Java 21) that parks cheaply when blocked |
| Carrier thread | The OS thread a virtual thread runs on |
| Pinning | A virtual thread stuck to its carrier while blocked (e.g. inside `synchronized`) |
| Platform thread | An ordinary OS-backed Java thread |
| Little's Law | L = λ × W: items in system = arrival rate × time each spends in it |
| Thundering herd | Many clients retrying at the same instant; fixed with jitter |
| Async dispatch | Container re-invokes the request pipeline when a `CompletableFuture` finishes |

---

## 10. If I had to defend this in a code review

- The bulkhead is verified by a test that measures the thing it promises (heartbeat latency during saturation), not just that a class exists.
- Fail-fast with the right status code, `Retry-After`, metrics and dashboard panels: overload is visible and self-explaining.
- **Weakest point:** the render itself is not interruptible, so a genuinely stuck Java2D render can hold a pool thread past the timeout; and the pool size is a guess until the owner's perf run gives real numbers. Fix: run the renderer out-of-process (libvips, Phase 14) where it can be killed, and size from measured throughput with Little's Law.
