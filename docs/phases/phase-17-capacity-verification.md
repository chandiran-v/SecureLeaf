# Phase 17 — 5,000-viewer capacity verification (MVP2 finish line)

> Spec for an unattended run. Decisions are numbered (D1…) and referenced from code comments.
> Requirements: **MVP2-07** (MVP2-01..04 combined raise the ceiling from 500 to 5,000+).
> Depends on: Phases 10–16. Target branch: `feature/secure-leaf-mvp2`.

## Context

Phases 11–16 each claimed an improvement. This phase **proves the combined effect** against the Phase 10
baseline, finds the next bottleneck, and writes the capacity report. A real 5,000-VU run needs
real hardware, which the owner runs. The automated run prepares everything, runs what fits in CI,
and makes the owner's run a one-command affair.

## Decisions

- **D1 — Scenario matrix** (`loadtest/capacity.js`):
  - ramps 500 → 1,000 → 2,500 → 5,000 VUs
  - a 10-minute hold at each step
  - a reader mix of 70% sequential, 20% back-navigation, 10% mobile variant
  - rate limits left at their production values

  Pass criteria per step: tile p95 < 500 ms, p99 < 1 s, error rate < 0.5% (excluding intended 429s), and no heap growth trend during a hold.
- **D2 — Distributed load generation.** Include instructions and a compose file for running k6 in several containers (or k6 operator notes), because one generator machine may itself become the bottleneck. The note covers "measure the load generator too".
- **D3 — Tuning checklist.** Settings to tune, and record:
  - Tomcat max threads / virtual threads
  - render pool size
  - HikariCP pool size (DB connections vs Postgres `max_connections`)
  - Lettuce connection settings
  - JVM flags (heap, GC choice G1 vs ZGC, with the reason)
  - the Phase 13 cache size

  Every change goes in `docs/perf/tuning-log.md` as *change → hypothesis → result*.
- **D4 — Automated-run scope.** The job runs the matrix scaled down (for example 20 → 50 → 100 VUs, 1-minute holds) against the app started in the job, to validate the scripts, thresholds and dashboards end to end. It commits those results clearly labelled "CI-scale, not capacity evidence".
- **D5 — Capacity report.** Write `docs/perf/capacity-report-mvp2.md`:
  - method
  - hardware
  - results table vs the Phase 10 baseline
  - which MVP2 change bought how much (attribute using the per-phase before/after numbers)
  - the current bottleneck and the next scaling step (horizontal scale-out with the Redis cache, a CDN for static assets, read replicas…)
  - cost per 1,000 concurrent viewers (estimate)

  Leave clearly marked `TODO(owner-run)` cells for the numbers only a full run can provide.
- **D6 — Regression guard.** Commit `loadtest/smoke.js` (20 VUs, 60 s, p95 < 500 ms), and deliver `docs/ci/perf-smoke.yml.example` so the owner can add a nightly perf smoke to CI.
- **D7 — MVP2 wrap-up.** Write `docs/release-mvp2.md`, mapping MVP2-01..07 to evidence, the same way as Phase 9's matrix.

## Acceptance criteria
1. `capacity.js` and `smoke.js` pass `k6 inspect`, or `node --check` if k6 is unavailable.
2. The CI-scale matrix ran, and its numbers are in the report, labelled.
3. The tuning log has at least one real change → result entry from the CI-scale run.
4. The report and the release matrix exist, with every MVP2 ID covered and every owner-run cell marked.
5. The Grafana dashboard includes a single "capacity" row: VUs vs p95, errors, CPU, threads, cache hit rate.

## Out of scope
- Kubernetes and autoscaling implementation.
- Multi-region.

## Learning note
Create `docs/learning-notes/phase-17-capacity-verification.md`. Headline topics:
- capacity planning
- finding the bottleneck (USE method in practice)
- Amdahl's law and why the next bottleneck moves
- connection-pool sizing maths
- GC choice
- the difference between a benchmark and a capacity test
- how to present performance work in an interview ("from 500 to 5,000: here's what moved each number")

Update the learning-notes README with "MVP2 complete".
