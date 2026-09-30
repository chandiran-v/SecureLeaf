# Phase 13 — watermarked tile cache: before / after

> **Status:** the k6 run (acceptance criterion 7) has **not been run**. The unattended CI job that built
> Phase 13 had no MinIO and no full stack, so no hit-rate or latency numbers are claimed here. Behaviour
> is covered by `TileCacheIT`, `DiskTileCacheTest` and `RedisTileCacheIT`.

## How to fill this in (owner)

Same method as [`README.md`](README.md). `loadtest/viewer.js` already models back-navigation:
raise `BACK_NAV_RATE` (default 0.1) to `0.4` to match the ~40 % assumption in `requirements.md`.

1. **Before:** `TILECACHE_TYPE=none`, `VUS=50,200,500 BACK_NAV_RATE=0.4`.
2. **After:** `TILECACHE_TYPE=disk` (default), identical run. Record the environment and SHA as in the baseline.
3. Read from Grafana ("Tile cache hit rate", "Tile cache size and evictions", "Render vs storage").

| | `none` | `disk` |
|---|---:|---:|
| Tile p50 / p95 / p99 | | |
| Cache hit rate (`hit / (hit + miss)`) | n/a | |
| `secureleaf_render_wait_seconds` p95 | | |
| Tile cache size at end of run (bytes) | n/a | |
| Evictions by reason | n/a | |

Expectation, not a result: with a 40 % back-navigation rate, a hit rate near 30–40 % (the first visit
of each page is always a miss) and a lower render queue wait.
