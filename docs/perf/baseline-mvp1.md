# MVP1 baseline

> Phase 10. **Status:** harness verified by a CI smoke run (below); the **full baseline is to be
> run by the owner on real hardware** and filled into the tables. Method: [`README.md`](README.md).

## 1. CI smoke — not a capacity result

Purpose: prove that seeder → k6 → metrics works end to end. Numbers are from a shared 4-vCPU
GitHub-hosted runner with k6, backend, Postgres and Redis on the **same** machine and with
**in-memory object storage instead of MinIO** (MinIO's image could not be pulled in the job), so
the storage-fetch time is meaningless and nothing here says anything about production capacity.

| | |
|---|---|
| Date | 2026-09-29 |
| Host | GitHub Actions runner, 4 vCPU, 16 GB, Ubuntu (x86-64) |
| Backend | `java -Xmx1g -XX:+UseG1GC`, Java 21, `loadtest` profile, 50 seeded buyers, 12-page book |
| k6 | v0.53.0, `VUS=20,20 STAGE_DURATION=30s THINK_TIME=1` (20 VUs, 60 s + 14 s ramp-down) |
| Storage | in-memory (test `InMemoryStorageService`), **not MinIO** |

| Result | Value |
|---|---|
| Tile requests (`http_req_duration{name:tile}`) | 928 |
| Tile latency p50 / p95 / max | 83 ms / 169 ms / 278 ms |
| Failed requests | 0 of 2038 (0.00 %) |
| Total throughput (all requests) | 27.3 req/s |
| Avg render time per tile (`secureleaf_watermark_render`) | 84 ms (of ≈ 90 ms server time per tile) |
| Avg storage fetch (in-memory) | < 0.01 ms |
| Avg tile size | 362 KB |
| Tomcat max threads (config) | 200 |
| GC | ≈ 0.45 s total pause over the run (all young collections) |
| Thresholds (tile p95 < 500 ms, errors < 1 %) | passed |

The one observation worth carrying forward: even in this tiny run almost all server time per tile
is the Java2D watermark render (CPU), not I/O. That is a hypothesis to test on real hardware, not a
conclusion.

## 2. Full baseline (owner to fill in)

Environment (fill in): CPU / cores · RAM · OS · JVM flags · commit SHA · k6 host · MinIO/Postgres
placement · date.

### Latency and throughput vs load (median of ≥ 3 runs, think time 5 s)

| VUs | Tile p50 | Tile p95 | Tile p99 | Tile req/s | Errors % | Busy threads (peak) | Backend CPU % (peak) | Heap used (peak) | GC pause s/s |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 50 | | | | | | | | | |
| 200 | | | | | | | | | |
| 500 | | | | | | | | | |

### Where the time goes (at the highest stable load)

| | Avg | p95 |
|---|---:|---:|
| Watermark render | | |
| Storage fetch (MinIO) | | |
| Whole `SecureTileService.getTile` | | |

### Bottleneck analysis

*(Fill in after the runs. First resource to saturate as VUs rise, with the dashboard evidence;
what it implies for phases 11–17. Measure only — no fixes in this document.)*

1. First saturation point:
2. Evidence:
3. Implication for MVP2 (rate limiting / render pool / tile cache / renderer):
4. Surprises:
