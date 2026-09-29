# Performance testing method

> Phase 10. MVP2 exists to go from ~500 to 5,000+ concurrent viewers, and the rule in
> [`requirements.md`](../requirements.md) is *don't optimise what you haven't measured*. This page is
> the method; results live in [`baseline-mvp1.md`](baseline-mvp1.md) and later reports (phases 11–17
> are judged against that baseline). Scripts: [`loadtest/`](../../loadtest/README.md).

## What is measured

| Signal | Meter (Prometheus name) | Why |
|---|---|---|
| Tile latency by outcome | `secureleaf_tile_request_seconds_*{outcome}` | The user-visible hot path; p50/p95/p99 |
| Watermark render time | `secureleaf_watermark_render_seconds_*{renderer}` | CPU-bound part of a tile |
| Storage fetch time | `secureleaf_storage_fetch_seconds_*` | I/O-bound part (MinIO) |
| Tile size | `secureleaf_tile_bytes_*` | Bandwidth per page |
| Active sessions | `secureleaf_viewer_sessions_active` | Concurrent readers |
| Tomcat threads | `tomcat_threads_busy_threads`, `tomcat_threads_config_max_threads` | Saturation (Little's Law) |
| JVM | `jvm_memory_used_bytes`, `jvm_gc_pause_seconds_*` | Heap pressure, GC stalls |

The dashboard is `infra/grafana/dashboards/secureleaf-viewer.json`; a unit test
(`GrafanaDashboardTest`) fails if it queries a series the code doesn't publish.

## Method (every run)

1. **Record the environment** in the report: CPU model/cores, RAM, OS, Docker versions, where
   k6 ran relative to the backend (same machine skews results — say so), JVM flags
   (`java -XX:+PrintFlagsFinal -version | grep -E 'MaxHeapSize|UseG1GC'`), backend commit SHA.
2. **Dataset:** the seeded 12-page book (`loadtest/loadtest-book.pdf`, ~150 DPI tiles ≈ 350 KB
   each) and N seeded buyers. Real content will differ; note it.
3. **Warm-up:** JIT compilation and connection pools make the first minute unrepresentative. Run a
   `VUS=20,20 STAGE_DURATION=1m` warm-up first, or ignore the first stage in the analysis.
4. **Run** `VUS=50,200,500` (or the plan in the report), think time 5 s, and leave the default
   thresholds. Do not change the code between runs of one comparison.
5. **Read the dashboard** during the run and record per stage:
   - *Latency:* tile p50/p95/p99 panel (use p95/p99, never only the average — the tail is what
     users feel).
   - *Where the time goes:* "render vs storage" panel. Render ≫ storage means CPU-bound → more
     cores/caching/faster renderer; storage ≫ render means I/O-bound.
   - *Throughput:* requests/s by outcome. Anything other than `ok` at volume is a finding.
   - *Saturation:* busy vs max Tomcat threads. Busy hitting max means requests queue
     (Little's Law: busy ≈ throughput × latency).
   - *Memory:* heap used vs max and GC pause per second. Rising GC time before latency does is an
     early warning.
   - *Host CPU:* `docker stats` / `top` on the backend host (CPU is not scraped in this phase).
6. **Repeat** at least 3 times for a real baseline; report the median and the spread.
7. **Write the bottleneck analysis:** the first resource to saturate as VUs rise, with evidence
   (which panel, which value), and what it implies for phases 11–17. Record, do not fix.

## Test types

Load (expected peak), stress (find the breaking point), soak (hours, find leaks) — see
[`loadtest/README.md`](../../loadtest/README.md). The baseline is a load test.

## Known limits of this harness

- k6 `ramping-vus` is a closed model (coordinated omission): it under-reports latency during
  stalls. Acceptable for readers; not for arrival-rate/capacity claims at the network edge.
- Tile requests carry one signed URL each and every VU reads the same book, so any future tile
  cache (phase 13) will look better here than with a diverse catalogue. Note the cache hit-rate
  assumption in those reports.
- Local runs share one machine between k6, backend, Postgres and MinIO. Full baselines go on the
  target hardware (Oracle 2 OCPU / 12 GB) with k6 on a *different* machine.
