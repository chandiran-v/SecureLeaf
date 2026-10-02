# SecureLeaf — MVP2 Requirements Traceability Matrix

> Phase 17, D7. Maps every MVP2 requirement id in [`docs/requirements.md`](requirements.md) (MVP2-01 … MVP2-07)
> to its evidence, the same way [`release-mvp1.md`](release-mvp1.md) does for MVP1. Companion documents:
> [`perf/capacity-report-mvp2.md`](perf/capacity-report-mvp2.md) (the numbers and what is still owed),
> [`perf/tuning-log.md`](perf/tuning-log.md), and the learning note
> [`learning-notes/phase-17-capacity-verification.md`](learning-notes/phase-17-capacity-verification.md).
>
> **Honesty note:** "Done" means built, merged and covered by a named test. It does **not** mean the
> performance claim attached to the requirement has been proven on real hardware: that is a separate column.
> Nothing below is upgraded to Done on the strength of a plan.

## Summary

| | |
|---|---|
| Built and tested | MVP2-02, 03, 04, 05, 06 |
| **Not built** | **MVP2-01** (libvips, Phase 14: awaiting an owner decision on how Java calls libvips) |
| Harness built, **full-scale proof owed by the owner** | MVP2-07 |
| Real-hardware before/after numbers for any MVP2 change | **none yet** (every per-phase `docs/perf/` page is unfilled) |

## The matrix

| ID | Status | Evidence (code / tests) | Performance evidence | Notes |
|----|--------|-------------------------|----------------------|-------|
| MVP2-01 libvips renderer | **Not done** | `WatermarkRenderer` Strategy exists (`content/watermark/WatermarkRenderer.java`, `Java2DWatermarkRenderer.java`); no libvips implementation. Spec: [`phases/phase-14-libvips-renderer.md`](phases/phase-14-libvips-renderer.md) | none | Phase 14 needs the owner's D1 decision (Java 25 + FFM, JNI, or a sidecar) and `.github/` workflow edits. **This is the biggest remaining lever:** render CPU is the measured bottleneck (capacity report §5). |
| MVP2-02 per-buyer tile cache | Done | `TileCacheIT` (`secondRequestSamePageSameSession_isHit_noStorageNoRender_sameBytes`, `differentSession_orDifferentUser_isMissWithDifferentBytes`, `afterRevocation_nextRequestIs403_eventhoughEntryIsCached`, `evictUser_forcesARender`); `DiskTileCacheTest`; `RedisTileCacheIT` | CI-scale only: ~5 % hit rate under the 70/20/10 mix ([`perf/ci-scale-results.md`](perf/ci-scale-results.md)). The "~40 %" in the requirement assumed every reader navigates back. `TODO(owner-run)`: [`perf/phase-13-tile-cache.md`](perf/phase-13-tile-cache.md) | Cache is authorise-first; hit/miss visible in the Capacity row. |
| MVP2-03 decoupled render pool | Done | `RenderBackpressureIT` (`watermarkRunsOnRenderThread_storageAndAccessLogDoNot`, `saturatedPool_rejectsImmediatelyWith503_andHeartbeatStaysFast`, `renderOverTimeout_returns503_andReleasesThePoolThread`) | CI-scale: at ~3× overload the system queued (p95 3 s) with **no failures and no shed**; queue wait was 95 % of the latency. `TODO(owner-run)`: [`perf/phase-12-render-pool.md`](perf/phase-12-render-pool.md) | The requirement said "backpressure via 429 or `DeferredResult`"; implemented as a bounded queue + 503 + `Retry-After` (Phase 12, D4). |
| MVP2-04 buyer rate limiting | Done | `RateLimiterTest`, `RateLimitIT`, `ClientIpResolverTest`; `scraper.js` k6 scenario | CI-scale: 0 × 429 for honest readers (never incremented). `TODO(owner-run)`: same at 5,000 VUs; scraper run per `loadtest/README.md` | Limits (tile 2/s burst 5) are enforced per buyer in Redis, fail open. |
| MVP2-05 document versioning | Done | `DocumentVersioningIT`, `VersionBackfillMigrationIT`, `PageLinksIT` | n/a (correctness feature) | Entitlements pin to the purchased version. |
| MVP2-06 adaptive tile resolution | Done | `AdaptiveTileVariantsIT` (`pipeline_rendersEveryVariantOfEveryPage_atTheExpectedWidths`, `mobileVariant_isSmaller_watermarked_andLoggedWithItsVariant`, `missingMobileVariant_fallsBackToDesktop`, `tamperedVariant_returns403_andNothingIsLogged`, `backfill_*`); frontend `tileVariant.test.ts`, `useTileVariant.test.tsx` | Stored size measured: MOBILE 210 KB vs DESKTOP 299 KB (−30 %). CPU/latency effect `TODO(owner-run)`: [`perf/phase-16-adaptive-tiles.md`](perf/phase-16-adaptive-tiles.md) | ~+70 % tile storage per page for the second variant. |
| MVP2-07 concurrency target 5,000+ | **Partial: harness done, proof owed** | `loadtest/capacity.js` (matrix, per-hold thresholds), `loadtest/smoke.js` (regression guard), `loadtest/heap-trend.js` (+ `heap-trend.test.js`), `loadtest/docker-compose.k6.yml` + `run-capacity.sh` (distributed generators), Grafana "Capacity" row (`GrafanaDashboardTest` keeps it honest), `docs/ci/perf-smoke.yml.example` | CI-scale matrix run and recorded, **labelled "not capacity evidence"**: CPU saturates at ~29 tiles/s on 4 shared vCPU; report cells for the 5,000-VU run are `TODO(owner-run)` | **Open risk:** one of the four contributing changes (01) is not built, and the model in the capacity report suggests the 5,000 target is only reachable at human think times (30–60 s) or with much more CPU than the 2-OCPU server. See [`perf/capacity-report-mvp2.md`](perf/capacity-report-mvp2.md) §5. |

## Owner-run checklist (everything this release still owes)

- [ ] **Decide Phase 14** (libvips): pick D1 option A/B/C, make the `.github/` edits, then run the phase. Re-run the matrix after.
- [ ] **The full capacity run:** seed `LOADTEST_USERS=5000`, `./loadtest/run-capacity.sh` ×3, once at `THINK_TIME=5` and once at `THINK_TIME=30`; fill every `TODO(owner-run)` in the capacity report.
- [ ] **A real "before"** (`git checkout 2e093c7`, `viewer.js`), because the Phase 10 full baseline was never run.
- [ ] Fill the per-phase before/after pages for Phases 12, 13 and 16 (`docs/perf/`).
- [ ] **Re-test the render pool size on the dedicated production-class host** (tuning-log entry 2).
- [ ] Turn on the nightly perf smoke: copy `docs/ci/perf-smoke.yml.example` to `.github/workflows/perf-smoke.yml`.
- [ ] Measure `render_avg` on the ARM server; the cost model is built on a Xeon figure.

## Known gaps that are not requirements

- `tomcat_threads_busy_threads` reads −1 under virtual threads; the older dashboard panel for it is now uninformative (the Capacity row uses the render pool instead).
- `jvm_gc_live_data_size_bytes` is G1-specific, so `heap-trend.js` needs G1 (the production default).
- Dev-profile SQL/security logging adds noise to load tests; use the flags in `docs/ci/perf-smoke.yml.example`.
