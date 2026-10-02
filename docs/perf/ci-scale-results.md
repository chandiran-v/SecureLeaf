# CI-scale matrix run — **not capacity evidence**

> Phase 17, D4. This is `loadtest/capacity.js` scaled down (20 → 50 → 100 VUs, 1-minute holds) and run
> against the app started in the job, to prove that the **scripts, thresholds, tags, Prometheus queries and
> dashboard work end to end**. The machine is a shared 4-vCPU runner running k6, the backend, Postgres and
> Redis together, with in-memory object storage and a 1-second think time. **Nothing here says anything
> about 5,000 viewers or about production hardware.** The full-scale run is `TODO(owner-run)` — see
> [`capacity-report-mvp2.md`](capacity-report-mvp2.md).

## Environment

| | |
|---|---|
| Date | 2026-10-02 |
| Host | GitHub Actions runner, Intel Xeon Platinum 8573C, 4 vCPU, 15 GB, Ubuntu (x86-64) — shared by k6, backend, Postgres 16 (docker), Redis 7 (docker), Prometheus (docker) |
| Backend | Java 21 (Temurin), `dev,loadtest` profiles with SQL/security logging turned down, `-Xmx1g -XX:+UseG1GC`, render pool 4 (= CPU count), commit = this branch (Phases 11–16 included) |
| Storage | **in-memory** (the test `InMemoryStorageService`), because MinIO's image could not be pulled in the job. Storage fetch time is therefore meaningless; the render is the whole cost |
| Data | 100 seeded buyers, the 12-page bundled book, tile cache `disk` (default), rate limits at their defaults |
| k6 | `grafana/k6:latest` (host network), `STEPS=20,50,100 HOLD=1m RAMP=15s THINK_TIME=1`, reader mix 70 % sequential / 20 % back-navigation (rate 0.4) / 10 % MOBILE |
| Thresholds | per hold: tile p95 < 500 ms, p99 < 1 s, errors < 0.5 % (429 excluded) |

## Result (control run, 4 render threads)

| Hold (VUs) | Tile p50 | **p95** | **p99** | Errors (non-429) | Tiles/s (Prometheus) | JVM CPU | Machine CPU | Render ms/tile | Render queue wait p95 | Queue peak | Verdict |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|:--|
| 20 | 113 ms | 217 ms | 278 ms | 0.00 % | 17.7 | 61 % | 64 % | 107 | 8 ms | 0 | pass |
| 50 | 910 ms | 1.19 s | 1.26 s | 0.00 % | 26.8 | 94 % | 99 % | 158 | 997 ms | 23 | **fail** (p95, p99) |
| 100 | 2.46 s | 3.02 s | 3.11 s | 0.05 % | 29.2 | 94 % | 99 % | 145 | 2.86 s | 72 | **fail** (p95, p99) |

(Latency columns: k6. Tiles/s, CPU, render and queue columns: Prometheus over the same hold window.)

What it shows, in order of importance:

1. **CPU saturates between 20 and 50 VUs and throughput stops growing at ~29 tiles/s.** Adding readers past
   that point only adds queue: latency grows linearly with VUs (1.19 s → 3.02 s as VUs double) while
   throughput is flat. This is the signature of a saturated resource, and the resource is CPU.
2. **Nearly all the latency is queue wait, not work.** At 100 VUs the render queue wait p95 is 2.86 s of the
   3.02 s tile p95; the render itself is ~145 ms. The Phase 12 bulkhead is doing its job (no shed, no 5xx), so
   the system degrades by getting slower, not by breaking.
3. **GC is not the problem:** ~7–9 ms of pause per second at saturation.
4. **The one failure mode is memory, not CPU:** 3 tiles failed with `OutOfMemoryError` at `-Xmx1g`
   (the 0.05 %). See [`tuning-log.md`](tuning-log.md) entry 3.
5. **The cache barely helps this mix:** hit rate about 5 %, because 70 % of readers never go back and only
   the 20 % back-navigation persona (40 % chance per page) ever repeats a page. The "~40 % hit rate" in
   `requirements.md` assumed *every* reader navigates back.
6. `tomcat_threads_busy_threads` reads −1 with virtual threads, so the old "Tomcat threads" panel is not
   meaningful any more; the capacity row uses the render pool and queue instead.

## Other runs (same scenario, one change each) — see the tuning log

| Run | hold-20 p95 | hold-50 p95 | hold-100 p95 | Tiles/s at 100 | Errors at 100 |
|---|---:|---:|---:|---:|---:|
| Control: 4 threads, G1, 1 GB (above) | 217 ms | 1.19 s | 3.02 s | 29.2 | 0.05 % |
| Control, dev logging left on (run 1) | 240 ms | 1.17 s | 3.07 s | 27.5 | 0 |
| Control, dev logging left on (run 2) | 253 ms | 1.26 s | 3.12 s | 27.3 | 0 |
| Render pool 8, 1 GB | 285 ms | 1.02 s | 2.61 s | 31.0 | 0 (log shows 15 OOMs) |
| Render pool 8, 2 GB | 244 ms | 1.08 s | 2.95 s | 27.9 | 0 |
| ZGC, 4 threads | 374 ms | 1.53 s | 3.87 s | 22.7 | 0 |

(The "pool 8, 1 GB" row lost 0.2 % of tiles to OOM in an earlier run of the same configuration; the row shown
is the later quiet-logging run, which had none. Both are recorded in the tuning log.)

## Heap trend (`loadtest/heap-trend.js`) — inconclusive at this scale

Run over the whole 4-minute window: live heap floor after GC 109 MB → 121 MB, slope 7.7 MB/min against a
limit of 5 MB/min: **FAIL, but not meaningful.** The window includes JIT warm-up and ramps, one-minute holds
are far too short for a leak to show above the noise of in-flight tiles (a queued tile is ~350 KB of live
heap), and the gauge is G1-specific (ZGC publishes zeros). The check is validated in the sense that it
runs, queries the right series and returns an exit code; its verdict needs the owner's 10-minute holds.
It is recorded as a failure rather than loosened until it passes.

## Acceptance of the harness itself

- `capacity.js` and `smoke.js` pass `k6 inspect`; the matrix ran to completion, per-step thresholds and
  `step` tags work (each hold reported separately, ramps excluded).
- The 429 exclusion was not exercised: no reader hit a limit (`tile_limited` was never incremented, so k6 prints no such metric), which is itself the
  expected result for honest readers.
- The error gate caught two invalid runs (tuning log entry 5).
- A Prometheus + dashboard check: every `secureleaf_*` series in the new Capacity row exists
  (`GrafanaDashboardTest`); the same series were queried by hand to produce the table above.
