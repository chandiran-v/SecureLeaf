# Tuning log (Phase 17, D3)

> Every change is recorded as **change → hypothesis → result**, including the ones that did not help.
> A tuning knob you can't show an effect for is a knob you don't understand yet.
> Method and caveats: [`README.md`](README.md). Results of the run: [`ci-scale-results.md`](ci-scale-results.md).

## Ground rules

1. **One change per run.** Same data, same VUs, same JVM otherwise, fresh JVM and fresh database each time.
2. **Repeat the baseline.** Two identical baseline runs differ by about 5 % (entries 0 and 1); a change smaller than that is noise.
3. **Read the saturation, not just the latency.** A change that moves p95 without moving the saturated resource is suspect.
4. **Say where it ran.** Entries 0–5 are **CI-scale** (4 vCPU shared by k6, backend, Postgres and Redis; in-memory storage). They show *what moves and in which direction*. They are **not** capacity evidence, and the sizes of the effects will differ on the owner's hardware.

## The checklist (D3) — what is tuned, what the default is, what the evidence says

| Knob | Default today | Where | Status |
|---|---|---|---|
| Tomcat threads / virtual threads | virtual threads on (`spring.threads.virtual.enabled`), so Tomcat's 200-thread cap does not apply; `tomcat_threads_busy` reads -1 | `application.yml` | Not tuned. Nothing to tune: request threads are not the bottleneck (entry 2 shows the render pool is). |
| Render pool size | CPU count (`render.pool.size`) | `RenderProperties` | **Tried** (entry 2). |
| Render queue / timeout | 200 / 5 s | `application.yml` | Not changed. At 100 CI VUs the queue peaked at 72 with no sheds. Re-evaluate at 5,000 VUs: 200 is shallow at ~1,000 tiles/s. |
| HikariCP pool | 10 (now explicit: `DB_POOL_MAX`) | `application.yml` | Not tuned; no run showed DB wait. Maths below. |
| Lettuce (rate limiter) | one shared connection, 500 ms timeouts, fail open | `RateLimitConfig.java` | Not tuned. One Lettuce connection multiplexes (pipelines) commands, so it does not need a pool. Watch `secureleaf_ratelimit_*` and Redis `instantaneous_ops_per_sec` at 5,000 VUs. |
| JVM heap | `-XX:MaxRAMPercentage=70` of the container limit (4 GB → 2.8 GB) | `backend.Dockerfile` | **Tried** (entry 3): 1 GB heap runs out under load. |
| GC | G1 | `backend.Dockerfile` | **Tried** (entry 4): ZGC was worse here. |
| Tile cache size | 2 GB disk, 15 min TTL | `application.yml` | Not tuned. The CI mix has a 5 % hit rate, so size is irrelevant there; needs the owner's run. |

**HikariCP sizing maths.** A connection is busy only while a query runs. By Little's Law the connections needed are `queries/s × seconds per query`. A tile request makes a handful of indexed single-row queries (session, entitlement, version); say 5 queries × 2 ms = 10 ms of connection time per tile. At 1,000 tiles/s that is 10 connections busy on average. Pool 10 is borderline at that rate, but the cache and the entitlement path will cut it; this is the number to watch (`hikaricp_connections_pending`) rather than a number to raise blindly. Raising it is bounded by Postgres `max_connections` (100 by default; the prod compose gives Postgres 1.5 GB, and each connection costs ~10 MB): `instances × pool size + admin/migrations headroom ≤ max_connections`. A pool bigger than the database can use just moves the queue inside Postgres.

## Entries

### 0. Baseline (quiet logging, 4 render threads, G1, 1 GB heap) — the control

- **Change:** none. Fresh DB and JVM; `loadtest` profile with SQL and Spring Security logging turned down; `STEPS=20,50,100 HOLD=1m RAMP=15s THINK_TIME=1`.
- **Result:** hold-20 p95 217 ms · hold-50 p95 1.19 s · hold-100 p95 3.02 s. Throughput plateaus at ~29 tiles/s with the machine at 99 % CPU. The same scenario with dev logging left on gave 3.07 s and 3.12 s at hold-100 (entry 1), so run-to-run spread is **~5 %**. (Latencies in this log are k6's `http_req_duration{name:tile}`; throughput, CPU and queue figures are from Prometheus over the same hold window.)

### 1. Turn off dev-profile SQL and security logging

- **Change:** `-Dspring.jpa.show-sql=false -Dlogging.level.com.secureleaf=INFO -Dlogging.level.org.springframework.security=WARN -Dlogging.level.org.springframework.graphql=WARN`. The `dev` profile logs every statement and every filter decision: 4.5 million lines in one 4-minute run.
- **Hypothesis:** logging is a noticeable share of CPU on a CPU-bound path, so p95 at saturation drops by 10 % or more.
- **Result:** hold-100 p95 3.07 s and 3.12 s (two dev-logging runs) → 3.02 s (quiet): **2–3 %, inside the noise.** Hypothesis not supported at this scale. It is still the right setting for a capacity run (it removes a variable and the log volume), and `docs/ci/perf-smoke.yml.example` uses it.

### 2. Render pool: 4 → 8 threads (2× the CPU count)

- **Change:** `-Drender.pool.size=8` on a 4-vCPU host.
- **Hypothesis:** the work is CPU-bound, so more threads than cores cannot help and should hurt (context switching, more memory in flight).
- **Result:** throughput at saturation **29.2 → 31.0 tiles/s (+6 %)**, hold-100 p95 **3.02 → 2.61 s (−14 %)**, hold-50 p95 1.19 → 1.02 s. Per-tile render time almost **doubled** (145 → 276 ms: threads now time-share the cores). Hypothesis **partly wrong**: a gain, but a small one, and the likely cause is that k6, Postgres and Redis share this machine; a bigger pool wins a bigger slice of the OS scheduler against them. On a dedicated backend host that advantage should disappear.
- **Decision:** **default unchanged** (`size = CPU count`). The effect is only marginally above the 5 % noise, it is probably an artefact of the shared host, and a larger pool raises peak memory (entry 3). **Re-test on the owner's hardware** (`TODO(owner-run)`).

### 3. Heap: 1 GB → 2 GB (with 8 render threads)

- **Change:** `-Xmx2g` (8 render threads).
- **Hypothesis:** each in-flight render holds several full-size images (a 1240×1754 page is ~8.7 MB as ARGB, a few copies per render), so a small heap fails under load while a larger one changes nothing about speed.
- **Evidence for the problem:** with `-Xmx1g`, `OutOfMemoryError: Java heap space` was thrown **15 times at 8 threads and 3 times at 4 threads** (0.2 % / 0.05 % of tiles failed, with `Failed to render watermark` to the client). With `-Xmx2g` and 8 threads: **0**.
- **Result:** errors gone; throughput 27.9 tiles/s, hold-100 p95 2.95 s (vs 2.61 s at 1 GB, which was losing tiles), i.e. **no speed change** (within the noise). Hypothesis confirmed. Heap is a correctness setting here, not a speed one.
- **Decision:** the production container gets `MaxRAMPercentage=70` of 4 GB = 2.8 GB, so it is above this line, but **size heap ≥ pool size × per-render working set × safety factor**; do not raise `render.pool.size` without raising the heap. The render pool bulkhead (Phase 12) bounds the concurrency, which is exactly what makes this sum possible.

### 4. GC: G1 → ZGC (generational)

- **Change:** `-XX:+UseZGC -XX:+ZGenerational` instead of `-XX:+UseG1GC`.
- **Hypothesis:** ZGC's near-zero pauses help tail latency. (The honest counter-hypothesis: ZGC trades throughput for pause time, and on a CPU-saturated 4-core box that trade loses.)
- **Result:** GC pause time per second **0.007 → 0.000**, but throughput **29.2 → 22.7 tiles/s (−22 %)** and hold-100 p95 **3.02 → 3.87 s (+28 %)**; hold-20 p95 217 → 374 ms. The counter-hypothesis wins. ZGC also does not publish `jvm_gc_live_data_size_bytes`, which `heap-trend.js` needs.
- **Decision:** **keep G1.** Pauses were only ~7 ms per second at saturation: GC was never the problem, so a pause-free collector had nothing to fix and its extra concurrent CPU work was pure cost. Revisit only if a full-scale run shows p99 spikes that line up with `jvm_gc_pause_seconds_max`.

### 5. The first "matrix" runs were invalid, and the error gate said so

- **Symptom:** a re-run showed `tile_errors` at **50 %** while every latency number looked wonderful (p95 14 ms).
- **Cause:** restarting the JVM wiped the in-memory object storage while the database kept the page rows, so every tile 500'd ("Object not found") in microseconds. A second occurrence: running `./mvnw test` while the app was up made Spring DevTools restart it in place, with the same effect.
- **Fix:** a fresh database *and* a fresh JVM for every run, and `-Dspring.devtools.restart.enabled=false`.
- **Lesson:** **latency without the error rate is meaningless**; a service that fails instantly is the fastest service. That is why the capacity thresholds pair p95/p99 with the error rate and why the report gives both.

### 6. Still to do on the owner's hardware (`TODO(owner-run)`)

| Knob | Question the full run answers |
|---|---|
| Render pool size on a *dedicated* backend host | Is entry 2's +6 % gone? What is the best size at 2 OCPU ARM? |
| Render queue capacity / timeout | At ~1,000 tiles/s does 200 shed too early, or does a 5 s timeout wait too long? |
| HikariCP pool | `hikaricp_connections_pending` at 5,000 VUs: non-zero means raise (within `max_connections`). |
| Tile cache size / TTL | Hit rate on the real mix; does 2 GB evict (`secureleaf_tilecache_evictions_total{reason="size"}`)? |
| Lettuce | Rate limiter latency and `fail open` count at 5,000 VUs. |
| GC re-check | If p99 spikes line up with GC pauses on the owner's JVM, re-test ZGC *there*. |
