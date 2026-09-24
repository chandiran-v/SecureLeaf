# Phase 10 — Observability & load-test harness (MVP2 foundation)

> Spec for an unattended run. Decisions are numbered (D1…) and referenced from code comments.
> Requirements: prerequisite for **MVP2-01..07**. MVP2 work is justified by measurement (see the MVP2 trigger in `docs/requirements.md`), so this phase comes first.
> Target branch: `feature/secure-leaf-mvp2`.

## Context

MVP2 exists to go from about 500 to 5,000+ concurrent viewers. The rule in `requirements.md` is: **don't optimise what you haven't measured.** This phase builds the tools to measure:
- metrics on the hot path (tile render)
- dashboards
- a repeatable load test

The baseline numbers it produces are what phases 11–17 are judged against.

## Decisions

- **D1 — Metrics.** Add `micrometer-registry-prometheus` and expose `/actuator/prometheus`, reachable from the Docker network only. In prod it is protected by a separate management port (`management.server.port: 8081`), not public.
- **D2 — Custom meters on the viewer path.** Each one is named in a single `ViewerMetrics` class:
  - `secureleaf.tile.request` — Timer with percentile histogram, tagged by `outcome` = ok, forbidden, superseded, not_found, error.
  - `secureleaf.watermark.render` — Timer: time spent only in `WatermarkRenderer`, tagged by `renderer`.
  - `secureleaf.storage.fetch` — Timer: tile fetch from MinIO.
  - `secureleaf.viewer.sessions.active` — Gauge: active leases.
  - `secureleaf.tile.bytes` — DistributionSummary.
  - Tomcat thread metrics: enable `server.tomcat.mbeanregistry.enabled=true`.
- **D3 — Local monitoring stack.** Add a `monitoring` profile to `infra/docker-compose.yml` with Prometheus plus Grafana. The Grafana dashboard JSON is committed at `infra/grafana/dashboards/secureleaf-viewer.json` and provisioned automatically. It shows:
  - tile p50/p95/p99
  - render vs storage time
  - requests/s by outcome
  - busy vs max Tomcat threads
  - JVM heap and GC
  - active sessions
- **D4 — Load-test data seeding.** A `loadtest` Spring profile with a `LoadTestSeeder` `ApplicationRunner` creates N buyers with ACTIVE entitlements for one multi-page product, from a bundled PDF. It writes their credentials to `loadtest/users.csv`, is idempotent, and **refuses to run in `prod`**.
- **D5 — k6 scenarios** in `loadtest/`:
  - `viewer.js`: each virtual user logs in, starts a session, heartbeats every 15 s, and reads pages sequentially at a configurable think time (default 5 s/page), with occasional back-navigation. This models real readers.
  - Stages are configurable by env: `VUS=50,200,500`.
  - Thresholds: `http_req_duration{name:tile} p(95)<500`, and errors below 1%.
  - `loadtest/README.md` explains how to run it (Docker k6 image; no local install).
- **D6 — Baseline report template.** Write `docs/perf/README.md` (method: hardware, JVM flags, dataset, warm-up, how to read the dashboard) and `docs/perf/baseline-mvp1.md`, with tables to fill in: VUs vs p95/p99, throughput, CPU, and threads, plus a bottleneck analysis section.
  - The automated run should download the k6 binary and run a **20-VU, 60-second smoke** against the app started in the job, then record those real numbers, labelled "CI smoke — not a capacity result".
  - If that isn't possible in the job, say so in the PR.
  - The owner runs the full baseline on real hardware.

## Acceptance criteria
1. `/actuator/prometheus` exposes every D2 meter after one tile request (integration test that scrapes and asserts the names).
2. Timer outcome tags are correct for ok, forbidden and superseded (integration test).
3. `docker compose --profile monitoring config` is valid, and the dashboard JSON parses. The dashboard's queries reference only meters that exist; a test or script greps the meter names.
4. The seeder creates N users with entitlements, a second run creates nothing new, and it throws under `prod`.
5. `loadtest/viewer.js` passes `k6 inspect`, or at least `node --check` if k6 is unavailable.
6. `docs/perf/*` exist, with the method section complete.

## Out of scope
- Any optimisation. This phase **only measures**.
- Distributed tracing (OpenTelemetry), which is a stretch follow-up.

## Learning note
Create `docs/learning-notes/phase-10-observability-load-testing.md`. Headline topics:
- the three pillars (metrics, logs, traces)
- percentiles vs averages (why p99 matters)
- histograms
- the USE and RED methods
- load vs stress vs soak tests
- Little's Law (concurrency = throughput × latency), applied to the Tomcat thread pool
- coordinated omission
- "measure before you optimise"
