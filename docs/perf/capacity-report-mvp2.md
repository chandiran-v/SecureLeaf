# MVP2 capacity report — 500 → 5,000 concurrent viewers (MVP2-07)

> Phase 17, D5. **Status: harness proven, CI-scale run done, full-scale run is `TODO(owner-run)`.**
> Every cell marked `TODO(owner-run)` needs the real hardware and a 5,000-VU run (about 1 hour of k6 per
> pass, plus load generators). Everything else here was measured or derived from something measured, and the
> label says which. **This report does not claim that 5,000 viewers work.** It says what the CI-scale run
> already tells us about *why they might not*, and what to run to find out.

## 1. Method

The scenario matrix is [`loadtest/capacity.js`](../../loadtest/capacity.js) (spec D1):

| | |
|---|---|
| Steps | 500 → 1,000 → 2,500 → 5,000 VUs, 2-minute ramp into each, **10-minute hold** at each |
| Reader mix | 70 % sequential, 20 % back-navigation (40 % chance per page), 10 % MOBILE variant — fixed per virtual user |
| Think time | 5 s per page (the Phase 10 baseline setting; see §5 for why this matters more than anything else) |
| Rate limits | production values, untouched. A 429 is counted (`tile_limited`) and excluded from the error rate |
| Pass criteria *per hold* | tile p95 < 500 ms · p99 < 1 s · errors < 0.5 % · no heap growth trend |
| Heap trend | `node loadtest/heap-trend.js` on `jvm_gc_live_data_size_bytes` over the hold (k6 cannot see heap) |
| Load generation | four k6 containers ([`docker-compose.k6.yml`](../../loadtest/docker-compose.k6.yml)), ideally on separate machines; **the generators are measured too** (`docker stats`, `http_req_blocked`, k6 vs backend latency) |
| Dashboard | Grafana "Capacity" row: VUs vs p95, errors, CPU, threads, cache hit rate |

One command for the owner (after seeding `LOADTEST_USERS=5000` and starting Prometheus):

```bash
BASE_URL=http://<backend>:8080 PROMETHEUS_URL=http://<prometheus>:9090 ./loadtest/run-capacity.sh
```

Do it **three times** and report the median and spread, as for the Phase 10 baseline. Also run it once with
the **realistic think time** (`THINK_TIME=30`, §5), because that is the number a business plan actually needs.

## 2. Hardware

| | CI-scale run (done) | Full run (`TODO(owner-run)`) |
|---|---|---|
| Backend host | GitHub runner, Xeon Platinum 8573C, 4 vCPU, 15 GB, **shared with k6, Postgres, Redis** | `TODO(owner-run)`: Oracle Always Free ARM, 2 OCPU / 12 GB (prod), or the larger box used for the experiment |
| Storage | in-memory (not MinIO) | MinIO, `TODO(owner-run)` |
| Load generators | same machine | `TODO(owner-run)`: 4 containers on ≥ 2 other machines |
| JVM | `-Xmx1g`, G1 | `TODO(owner-run)`: record `-XX:+PrintFlagsFinal` |
| Commit | this branch | `TODO(owner-run)` |

## 3. Results against the Phase 10 baseline

The Phase 10 *full* baseline ([`baseline-mvp1.md`](baseline-mvp1.md) §2) was never run, so there is **no real
"before" number at scale**. The only Phase 10 measurement is its CI smoke (20 VUs, 30-s stages, 1-s think,
4-vCPU runner, in-memory storage): tile p95 **169 ms**, 0 % errors. The CI-scale hold at 20 VUs in this phase
(same kind of host, same think time) gave p95 **217 ms**. These are not comparable (different mix, different
steps, different run lengths, a MOBILE persona, a back-navigation persona, ~5 % run-to-run noise on top), and
they certainly do not show MVP2 got *slower*; they show that at 20 VUs nothing is saturated either way.

**Before / after, owner to fill in** (same hardware, same data; *before* = `git checkout 2e093c7`, the Phase 10
merge, using `viewer.js` with `VUS=500,1000,2500,5000` — it will fail early, which is the point):

| Hold | Before (Phase 10): p95 / p99 / errors | After (MVP2): p95 / p99 / errors | Heap slope (MB/min) | Verdict |
|---:|---|---|---|---|
| 500 | `TODO(owner-run)` | `TODO(owner-run)` | `TODO(owner-run)` | `TODO(owner-run)` |
| 1,000 | `TODO(owner-run)` | `TODO(owner-run)` | `TODO(owner-run)` | `TODO(owner-run)` |
| 2,500 | `TODO(owner-run)` | `TODO(owner-run)` | `TODO(owner-run)` | `TODO(owner-run)` |
| 5,000 | `TODO(owner-run)` | `TODO(owner-run)` | `TODO(owner-run)` | `TODO(owner-run)` |

**CI-scale results (labelled: not capacity evidence).** Full detail in
[`ci-scale-results.md`](ci-scale-results.md):

| Hold | p95 | p99 | Errors | Tiles/s | CPU (machine) | Verdict |
|---:|---:|---:|---:|---:|---:|:--|
| 20 VUs | 217 ms | 278 ms | 0.00 % | 17.7 | 64 % | pass |
| 50 VUs | 1.19 s | 1.26 s | 0.00 % | 26.8 | 99 % | fail |
| 100 VUs | 3.02 s | 3.11 s | 0.05 % | 29.2 | 99 % | fail |

## 4. What bought how much (attribution)

Spec D5 asks to attribute the gain to each change "using the per-phase before/after numbers". **Those numbers
do not exist yet**: the before/after pages for Phases 12, 13 and 16 ([`phase-12-render-pool.md`](phase-12-render-pool.md),
[`phase-13-tile-cache.md`](phase-13-tile-cache.md), [`phase-16-adaptive-tiles.md`](phase-16-adaptive-tiles.md)) all say
"not run", and Phase 14 was never built. So this table states, per change, the *mechanism*, what *is* known, and
what the owner's run must show. Filling it with plausible-looking figures would be worse than leaving it empty.

| MVP2 change | What it does to the saturated resource (CPU) | Measured so far | Contribution to the 5,000 ceiling |
|---|---|---|---|
| **11 — rate limiting** (MVP2-04) | Removes *abusive* load (scrapers), none of the honest load | Behaviour tested (`RateLimiterTest`, `RateLimitIT`); at honest load no 429 was ever seen | Protects the ceiling rather than raising it. `TODO(owner-run)`: confirm 0 × 429 at 5,000 honest VUs |
| **12 — render pool + backpressure** (MVP2-03) | Bounds concurrent renders at the CPU count, moves them off request threads, sheds with 503 | CI-scale: at 100 VUs / 2.9× overload the system degraded by queueing (p95 3 s) with **no failures and no shed**; heartbeat stays fast during saturation (`RenderBackpressureIT`) | Does not add capacity; makes overload **survivable and visible** (queue depth, wait time). `TODO(owner-run)`: shed rate at 5,000 |
| **13 — tile cache** (MVP2-02) | Skips the render on a hit | CI mix: **~5 % hit rate** (only the 20 % back-nav persona repeats pages). Same 12-page book for every reader | At this mix it buys ~5 % of the render CPU. Real value depends entirely on real navigation behaviour. `TODO(owner-run)`: hit rate at scale |
| **14 — libvips** (MVP2-01) | Would cut render CPU per tile | **Not built** (blocked on the Java 25 / FFM owner decision) | **None.** Render stays Java2D, ~0.13 core-seconds per tile (§5), so this is the largest lever not yet pulled |
| **15 — versioning** (MVP2-05) | None on capacity | — | Neutral. Correctness feature |
| **16 — adaptive resolution** (MVP2-06) | MOBILE renders 53 % of the pixels | Stored size: MOBILE 210 KB vs 299 KB (−30 %). CPU saving is an expectation (render cost ∝ pixels) not a result | With 10 % MOBILE readers: ≈ −5 % render CPU. `TODO(owner-run)` DESKTOP vs MOBILE k6 run per [`phase-16-adaptive-tiles.md`](phase-16-adaptive-tiles.md) |

The honest reading: **of the four changes MVP2-07 credits with raising the ceiling to 5,000 (MVP2-01…04),
one was never built (01), one is a safety net not an accelerator (03), one is a guard against abuse (04), and
one (02) helps by exactly the hit rate**, which is low for this traffic mix. Whether the product reaches 5,000
therefore depends far more on the think time and the hardware (§5) than on the MVP2 phases.

## 5. The current bottleneck, and the model that predicts it

**Bottleneck: CPU, in the Java2D watermark render.** Evidence (CI-scale): machine CPU 99 % from 50 VUs up;
throughput flat at ~29 tiles/s; render queue wait p95 2.86 s of a 3.02 s tile p95; GC pauses ~8 ms/s; request
threads (virtual) idle. This is the USE method: the one resource at 100 % utilisation with a queue in front
of it is the bottleneck.

Measured cost: JVM CPU 94 % of 4 cores = 3.76 cores for 29.2 tiles/s → **≈ 0.13 core-seconds per tile**
(includes the GraphQL calls, signing, JSON, GC; excludes k6, Postgres, Redis).

By Little's Law, concurrent readers `N`, think time `Z` and (small) latency `R` give throughput
`X ≈ N / (Z + R)`; the CPU needed is `X × 0.13 × (1 − hit rate)`:

| Concurrent readers | Think time | Tiles/s | Cores needed (0 % hits) | Cores needed (30 % hits) |
|---:|---:|---:|---:|---:|
| 500 | 5 s | ~100 | 13 | 9 |
| 5,000 | 5 s | ~1,000 | **130** | 91 |
| 5,000 | 30 s | ~167 | **22** | 15 |
| 5,000 | 60 s | ~83 | **11** | 8 |

(Xeon x86 numbers; the ARM Neoverse cores of the production box are `TODO(owner-run)` — measure
`render_avg` there before trusting any row.)

Read that table twice. **The 5,000-viewer target is a statement about think time as much as about software.**
At the benchmark's 5-second think time, 5,000 readers need ~130 cores of Java2D rendering, which no single
server delivers however well tuned; at a human-realistic 30–60 seconds per page it is 11–22 cores, which is a
small cluster or a larger single machine. The production box has 2 OCPU, which by this model carries about
**15 tiles/s ≈ 450–900 readers at 30–60 s think time** (`TODO(owner-run)` to confirm). Amdahl's law applies
too: whatever lever is pulled next (cache, libvips), the *next* bottleneck is waiting behind it: Postgres
connections (10 in the pool), the Redis round trips for rate limiting and session leases, bandwidth (§6, item 6), and
the MinIO fetch (not exercised in the CI-scale run).

## 6. Next scaling steps, in order

1. **Pull the biggest lever: libvips (Phase 14).** Render CPU is the bottleneck, and libvips is typically
   several times cheaper per tile. Re-run this matrix after it; the report's §4 gets a real row.
2. **Cache what is repeated, and measure the real hit rate** before sizing anything. If readers rarely go
   back, a bigger cache buys nothing; the cache *key* (per buyer) also means no sharing between buyers. A
   shared, un-watermarked-then-stamped design is a different product trade-off (forensics vs cost) — see the
   Phase 13 note.
3. **Horizontal scale-out of the backend** behind Caddy (or a load balancer) with `TILECACHE_TYPE=redis`, so a
   reader's tiles survive a hop between instances. Render CPU scales linearly with instances, which is why this
   works for a CPU-bound tile service; the shared pieces are Postgres, Redis and MinIO.
4. **A CDN for static assets** (the SPA bundle, fonts, thumbnails, covers). Tiles themselves cannot go on a CDN
   as-is, because each is unique per buyer and watermarked; but every static byte the backend stops serving
   is bandwidth and threads freed.
5. **Postgres read replicas** only if `hikaricp_connections_pending` or query time shows up in the capacity row;
   it has not in any measurement so far. The cheaper first step is the pool setting (`DB_POOL_MAX`).
6. **Bandwidth.** ~350 KB per tile × 1,000 tiles/s is ~350 MB/s ≈ 2.8 Gbit/s of egress at the 5 s think time;
   at 30 s it is ~470 Mbit/s. Either exceeds a typical single-VM NIC or a free-tier egress quota: another reason
   the realistic think time matters, and a reason MOBILE variants are more than a nicety.

## 7. Cost per 1,000 concurrent viewers (estimate)

Assumptions, all stated so they can be challenged: 30-second think time (≈ 33 tiles/s per 1,000 readers), 0.13
core-seconds per tile, 0 % cache hits, **50 % target utilisation** so a spike does not breach the p95, list
prices for OCI Ampere A1 pay-as-you-go (about US$0.01 per OCPU-hour and US$0.0015 per GB-hour, to be
re-checked) and 4 GB of RAM per OCPU for render memory (§ tuning-log entry 3).

- Cores: 33 × 0.13 / 0.5 ≈ **8.6 cores** (assuming an ARM core ≈ an x86 vCPU: `TODO(owner-run)`)
- ≈ 9 OCPU × US$0.01 × 730 h ≈ US$66/month + 36 GB × US$0.0015 × 730 h ≈ US$39/month
- **≈ US$105 per month per 1,000 concurrent viewers, ≈ US$0.10 per concurrent viewer-month**, before
  bandwidth, Postgres, Redis, MinIO and the load balancer (which at ~95 Mbit/s of tile traffic per 1,000 viewers, are not free).

It scales almost linearly with render CPU, so **halving the render cost (libvips) halves the bill**; this is the
number to put next to any proposal that costs developer time. The 2-OCPU always-free server costs nothing and
carries roughly the first few hundred readers. All of it is an estimate until the owner's run replaces
the 0.13 with a measured figure on target hardware.

## 8. Known limits of this report

- One workload: a single 12-page book read by every VU, so storage and cache behaviour are optimistic.
- Closed-loop load (`ramping-vus`): when the server slows, k6 sends less. Honest readers behave this way, but
  it hides the latency of a stall (coordinated omission), so use the tail, not the mean.
- The CI-scale host shared its CPU between k6 and the backend; absolute numbers there are pessimistic for the
  backend, and effects under ~5 % are noise.
- Heap-trend is meaningful only for the 10-minute holds.
