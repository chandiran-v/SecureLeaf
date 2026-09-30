# Phase 12 — render pool: before / after

> **Status:** the k6 smoke re-run (acceptance criterion 6) has **not been run**. The unattended CI job
> that built Phase 12 had no MinIO and no full stack, so no numbers are claimed here. The
> bulkhead behaviour itself is covered by `RenderBackpressureIT` (criteria 1–4, passing).

## How to fill this in (owner)

Same method as [`README.md`](README.md); do not change anything else between the two runs.

1. **Before:** check out the commit before Phase 12 (`git checkout 863bb99`), run
   `VUS=20,20 STAGE_DURATION=30s THINK_TIME=1` (smoke) and, for a saturation view,
   `VUS=50,200,500` as in [`baseline-mvp1.md`](baseline-mvp1.md).
2. **After:** repeat on the Phase 12 commit. Optionally add
   `-XX:StartFlightRecording=filename=pin.jfr,settings=profile` to the backend and inspect
   `jdk.VirtualThreadPinned` events: `jfr print --events jdk.VirtualThreadPinned pin.jfr`.
3. Record the environment (CPU, RAM, JVM flags, k6 placement, SHA) as in the baseline.

| | Before (`863bb99`) | After (Phase 12) |
|---|---:|---:|
| Tile p50 / p95 / p99 | | |
| Failed requests % (expect 0 at smoke load) | | |
| `secureleaf_render_rejected_total` (expect 0 at smoke load) | n/a | |
| `secureleaf_render_wait_seconds` p95 | n/a | |
| Tomcat busy threads (peak) | | |
| GC pause s/s | | |

At overload (500+ VUs) expect the *shape* to change: before, tile latency climbs for everything;
after, some tiles get 503 quickly while non-tile latency (GraphQL, heartbeat) stays flat. k6 counts a
503 as a failed request, so `http_req_failed` will rise under deliberate overload; that is the
system working, not a regression.

## Pinning check

Static inspection (Phase 12): the only `synchronized` on the request path is the one-time lazy
init in `RateLimiter.proxyManager()`; `MockRazorpayGateway` (dev/mock only) synchronizes short
methods that do not block on I/O. Runtime JFR confirmation is **not done** — see step 2.
