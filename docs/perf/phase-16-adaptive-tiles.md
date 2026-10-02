# Phase 16 — adaptive tile resolution: DESKTOP vs MOBILE

> **Status:** the k6 comparison (acceptance criterion 7) has **not been run**. The unattended CI job that
> built Phase 16 had no full stack, so no latency or per-request numbers are claimed. What *was*
> measured is the stored tile size, below. Behaviour is covered by `AdaptiveTileVariantsIT`,
> `Java2DWatermarkRendererTest` and the frontend chooser tests.

## Measured: stored (un-watermarked) tile size

From `LoadTestSeederIT` on the bundled 12-page load-test book, rendered by the real pipeline
(CI runner, PDFBox, PNG):

| Variant | Pages | Mean width | Mean file size |
|---|---:|---:|---:|
| `DESKTOP` (150 DPI) | 12 | 1239 px | 299 141 bytes |
| `MOBILE` (900 px) | 12 | 900 px | 209 760 bytes |

MOBILE has 53 % of the pixels (900² / 1240²) but **70 %** of the bytes. PNG is lossless and text-heavy
pages compress well, so bytes fall more slowly than pixels. The watermarked response is a little larger
than either (the mark is extra entropy), by a similar amount for both. Watermark CPU scales with pixel
count, so MOBILE's render cost should drop by roughly the pixel ratio; that is an expectation, not a result.

Storage cost: a second variant adds ~70 % to the tiles bucket (299 + 210 = 509 KB per page instead of 299 KB).

## How to fill in the k6 result (owner)

Method as in [`README.md`](README.md). `loadtest/viewer.js` now takes `VARIANT` and records a
`tile_bytes` trend, so the two runs are directly comparable. Same seeded data, same `VUS`, same SHA:

```bash
docker run --rm -i --add-host=host.docker.internal:host-gateway -v "$PWD/loadtest:/loadtest" \
  -e VARIANT=DESKTOP -e VUS=50,200,500 grafana/k6 run /loadtest/viewer.js
docker run --rm -i --add-host=host.docker.internal:host-gateway -v "$PWD/loadtest:/loadtest" \
  -e VARIANT=MOBILE  -e VUS=50,200,500 grafana/k6 run /loadtest/viewer.js
```

Run with `TILECACHE_TYPE=none` for both, otherwise the cache hides the render cost.

| | `DESKTOP` | `MOBILE` |
|---|---:|---:|
| `tile_bytes` mean / p95 (k6) | | |
| Tile p50 / p95 / p99 (`http_req_duration{name:tile}`) | | |
| `secureleaf_watermark_render_seconds` mean (Grafana, "Render vs storage") | | |
| `secureleaf_render_wait_seconds` p95 | | |

## Follow-up: WebP / AVIF

Out of scope here. Lossy WebP/AVIF would cut bytes far more than a smaller PNG, at a CPU cost for encoding
(AVIF is slow) and with browser-support caveats (AVIF needs a recent browser). Worth measuring after the
variants are live.
