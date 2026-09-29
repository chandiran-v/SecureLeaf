# Phase 10 — Observability & load-test harness (MVP2 foundation)

> **Status:** Done
> **Built:** 2026-09-29
> **Requirement IDs covered:** prerequisite for MVP2-01..07 (no requirement of its own — it builds the tools that justify them). Spec: `docs/phases/phase-10-observability-load-testing.md`.
> **Commits:** see the `auto/issue-10` branch (`Phase 10: …`)

---

## 1. What we built, in plain English

MVP2's goal is to serve about ten times more simultaneous readers. Before anyone changes a line to
make things faster, we need to know **what is slow and by how much** — otherwise we are guessing,
and guesses about performance are wrong surprisingly often. This phase builds the measuring
equipment: numbers on the page-tile path, a dashboard to see them, fake readers to generate load,
and a report template to write the results in.

Concretely: the backend now publishes metrics at `/actuator/prometheus` (how long each page tile
took, split into "fetch from storage" and "draw the watermark", how many readers are active, how
busy the web server's threads are). A local Prometheus collects them every 5 seconds and a Grafana
dashboard, committed to the repo and loaded automatically, draws them. A seeder creates hundreds of
test buyers who each own a book, and a k6 script plays each of them: log in, open the book, keep the
session alive, read page after page with a pause like a human. Running it and reading the dashboard
gives the **baseline** that phases 11–17 will be compared against.

**Before this phase:** the only observability was liveness/readiness probes and a mail-failure counter; "is the viewer fast?" had no answer.
**After this phase:** one command starts a monitoring stack, one starts a realistic load, and the dashboard says where time goes.

---

## 2. Why it matters

- **"Measure before you optimise."** Optimising an unmeasured system means you may speed up the 5% and leave the 95%. Our own smoke run already hints the watermark render, not MinIO, dominates a tile — a guess we would have got backwards if we had assumed "storage is slow".
- **A baseline is a contract.** "Phase 13's cache made p95 go from X to Y" is only a claim if X was recorded first, on the same method.
- **Skip it and:** MVP2 phases become opinion, regressions are invisible until users complain, and nobody can say whether 5,000 viewers was reached.

---

## 3. New concepts introduced

### 3.1 The three pillars of observability

**What it is:** metrics (numbers over time), logs (events with detail), traces (one request's path across components).
**The analogy:** a car. Metrics are the dashboard gauges (speed, temperature), logs are the black-box recorder of what happened, a trace is the GPS track of one journey.
**Why we needed it here:** we already had logs (Phase 9: structured JSON, correlation ids). Phase 10 adds metrics, which are what answer "how slow, how often, how loaded". Traces (OpenTelemetry) are explicitly out of scope — a stretch follow-up.
**What breaks without it:** logs alone can't tell you the p99 latency; you would have to parse millions of lines.

### 3.2 Percentiles vs averages (why p99 matters)

**What it is:** p95 = "95% of requests were faster than this." Average = the sum divided by the count.
**The analogy:** a restaurant that serves 99 diners in 10 minutes and one in 2 hours has an "average" of 11 minutes — and one furious customer. The average hides the person who leaves a review.
**Why here:** the k6 threshold is `p(95) < 500 ms` on tiles, and the dashboard shows p50/p95/p99. A reader flips pages constantly, so the slow tail is hit within a few pages: at 1% slow requests, a 30-page read very likely includes one.
**How it works:** latency distributions are skewed (a long right tail from GC pauses, lock waits, queues). Averages are dragged by outliers and hide them at the same time; percentiles describe the shape.
**In our code:** `backend/src/main/java/com/secureleaf/viewer/metrics/ViewerMetrics.java:79` (`publishPercentileHistogram()`) and the dashboard's `histogram_quantile(0.99, …)`.
**What breaks without it:** "average latency 80 ms, all good" while one in a hundred pages takes two seconds.

### 3.3 Histograms

**What it is:** instead of storing every latency, count how many requests fell into each bucket (≤5 ms, ≤10 ms, ≤25 ms …).
**The analogy:** a tally chart of "how many people were this tall" instead of a list of everyone's height.
**Why here:** Prometheus can't ship every observation; it scrapes bucket counters and `histogram_quantile` estimates percentiles from them. That is why the Timer uses `publishPercentileHistogram()` — without it, only count/sum/max exist, and you can compute an average but never a p99.
**Trade-off:** buckets are an approximation (accuracy depends on bucket edges) and each bucket is a time series (cardinality cost). Percentiles computed *client-side* cannot be averaged across instances; histogram buckets can be summed — which matters the day there are two servers.
**What breaks without it:** no percentile panels at all.

### 3.4 The USE and RED methods

**What they are:** two checklists for what to graph. **RED** (for services): **R**ate, **E**rrors, **D**uration. **USE** (for resources): **U**tilisation, **S**aturation, **E**rrors.
**The analogy:** RED is how the customers experience the shop (queue length, refused orders, wait time); USE is the health of each machine in the kitchen (how busy, is there a backlog, is it broken).
**Why here:** the dashboard is RED for the tile endpoint (requests/s by outcome, duration percentiles) plus USE for the resources behind it (Tomcat threads busy vs max = utilisation/saturation, JVM heap and GC).
**What breaks without it:** dashboards with forty unrelated graphs and no story.

### 3.5 Load vs stress vs soak tests

- **Load:** the expected peak — does it meet the target? (`VUS=50,200,500`)
- **Stress:** beyond the peak — how does it fail, and does it recover?
- **Soak:** moderate load for hours — memory leaks, connection-pool exhaustion.
**The analogy:** a bridge — cross it with the expected traffic, keep adding trucks until it creaks, then leave the traffic on for a week to watch for fatigue.
**Why here:** Phase 10 builds the harness and a load-shaped baseline; stress belongs to phase 17's capacity claim; soak is the way to catch leaks in the lease/session code.

### 3.6 Little's Law (concurrency = throughput × latency)

**What it is:** the average number of things in a system equals arrival rate × average time each spends there. L = λ × W.
**The analogy:** a shop with 2 checkouts where each customer takes 3 minutes can serve 40 customers an hour. More arrive than that and the queue grows without limit.
**Why here:** Tomcat has 200 worker threads (`tomcat_threads_config_max_threads`). A tile takes ~90 ms of thread time, so 200 threads support at most ≈ 200 / 0.09 ≈ 2,200 tiles/s **if** the CPU could keep up — in reality the CPU saturates first (a 2-core server can only render ~ 2 / 0.084 ≈ 24 tiles/s!). The busy-vs-max panel shows the queue forming. With 500 readers each fetching a tile every 5 s the load is 100 tiles/s — the arithmetic tells us before the test that a 2-OCPU server has a problem, and the baseline confirms or refutes it.
**What breaks without it:** raising `server.tomcat.threads.max` "to handle more load" when the real limit is CPU just makes the queue longer and the latency worse.

### 3.7 Coordinated omission

**What it is:** a load-test flaw where a stalled server makes the tester send fewer requests, so the worst latencies are never recorded.
**The analogy:** a pollster who stops phoning people while the phone network is down, then reports "no complaints".
**Why here:** k6's `ramping-vus` executor is *closed*: each VU waits for its response before sending the next. That models readers well (they wait for a page) but under-reports at saturation. For a request-rate claim, use `ramping-arrival-rate` (open model). We documented this in `loadtest/README.md` and `docs/perf/README.md` instead of hiding it.
**What breaks without knowing it:** a beautiful p99 from a test that stopped pushing exactly when the server suffered.

### 3.8 Micrometer, Prometheus and Grafana (the toolchain)

**What they are:** *Micrometer* is the metrics facade inside Spring Boot (like SLF4J for logs); *Prometheus* is a time-series database that **pulls** ("scrapes") `/actuator/prometheus` on an interval; *Grafana* draws graphs from Prometheus queries (PromQL).
**Why pull, not push:** the server needs no knowledge of the monitoring system; a dead target shows up as a failed scrape (`up == 0`).
**In our code:** `backend/pom.xml` (`micrometer-registry-prometheus`), `infra/prometheus/prometheus.yml`, `infra/grafana/`.
**Naming gotcha:** Micrometer names use dots (`secureleaf.tile.request`); Prometheus renames them (`secureleaf_tile_request_seconds_count`), adding the unit and `_total` for counters.

### 3.9 Timer.Sample — a timer whose tag isn't known until the end

**What it is:** `Timer.start(registry)` records the start; `sample.stop(timer)` records the elapsed time on a timer you choose *afterwards*.
**Why here:** the `outcome` tag (ok, forbidden, superseded, not_found, error) is only known once the method finished or threw.
**In our code:** `SecureTileService.java:68-78` and `ViewerMetrics.recordTileRequest`.
```java
Timer.Sample sample = metrics.startTileRequest();
try {
    byte[] tile = serveTile(request);
    metrics.recordTileBytes(tile.length);
    metrics.recordTileRequest(sample, ViewerMetrics.OUTCOME_OK);
    return tile;
} catch (RuntimeException e) {
    metrics.recordTileRequest(sample, ViewerMetrics.outcomeOf(e));
    throw e;
}
```
**What breaks without it:** a single untagged timer mixes 5 ms rejections (bad signature) with 90 ms successes, so the "p95" describes neither.

### 3.10 Metric cardinality

**What it is:** every unique combination of tag values is its own time series stored forever.
**Why here:** `outcome` has 5 values and `renderer` 1–2 — fine. Tagging a tile by `userId` or `sessionId` would create thousands of series and can take Prometheus down. Tags must be low-cardinality; per-user detail belongs in logs.

### 3.11 Separate management port

**What it is:** Spring Boot can serve `/actuator/*` on a different port from the app (`management.server.port`).
**Why here:** the metrics endpoint is unauthenticated (Prometheus has no JWT). In prod it moves to port 8081, which Docker never publishes; Caddy forwards only `/actuator/health` to it. `ManagementPortIT` proves that layout with Spring Security in front.
**What breaks without it:** anyone on the internet reads your internal counters (request volumes, JVM details) — useful reconnaissance.

---

## 4. Best practices applied

| Practice | What we did | Why it matters | Where |
|---|---|---|---|
| One class names every meter | `ViewerMetrics` constants | Dashboard, tests, docs can't drift from typos | `viewer/metrics/ViewerMetrics.java:28` |
| Low-cardinality tags only | `outcome`, `renderer` | Prometheus memory stays bounded | `ViewerMetrics.java:78` |
| Fail the build if the dashboard lies | `GrafanaDashboardTest` cross-checks queries against `ViewerMetrics` | Prevents empty panels discovered mid load-test | `src/test/.../GrafanaDashboardTest.java` |
| Dashboards as code | JSON committed + auto-provisioned | Reproducible, reviewable, no click-ops | `infra/grafana/` |
| Guard dangerous tooling | Seeder throws under `prod` | It creates known-password accounts | `LoadTestSeeder.java:129` |
| Idempotent seeding | Look up by email/slug before create; recovers a half-created product | Safe to re-run at any time | `LoadTestSeeder.java:147` |
| Threshold-driven load test | k6 `thresholds` fail the run | CI-able pass/fail, not eyeballing | `loadtest/viewer.js` `options.thresholds` |
| Honest labels | "CI smoke — not a capacity result" | A number without its context misleads | `docs/perf/baseline-mvp1.md` |
| Metrics off the public port | `management.server.port: 8081` in prod | Internal counters stay internal | `application-prod.yml`, `Caddyfile` |

---

## 5. What does what — file map

| File | Responsibility |
|---|---|
| `backend/.../viewer/metrics/ViewerMetrics.java` | Defines and records every D2 meter; maps exceptions to `outcome` tags |
| `backend/.../viewer/service/SecureTileService.java` | Wraps `getTile` in a timer; times storage fetch and watermark render |
| `backend/.../viewer/repository/ViewerSessionRepository.java` | `countByEndedAtIsNull()` feeding the active-sessions gauge |
| `backend/.../auth/security/SecurityConfig.java` | Lets Prometheus scrape `/actuator/prometheus` without a JWT |
| `backend/src/main/resources/application*.yml` | Exposes `prometheus`; Tomcat MBean registry; prod management port |
| `backend/.../loadtest/LoadTestSeeder.java` | `loadtest`-profile runner: buyers + entitlements + `loadtest/users.csv` |
| `backend/src/main/resources/loadtest/loadtest-book.pdf` | The 12-page bundled PDF the seeder uploads |
| `infra/docker-compose.yml` (`monitoring` profile) | Prometheus + Grafana |
| `infra/prometheus/prometheus.yml`, `infra/grafana/**` | Scrape config, datasource + dashboard provisioning, the dashboard JSON |
| `loadtest/viewer.js`, `loadtest/README.md` | The k6 reader scenario and how to run it |
| `docs/perf/README.md`, `docs/perf/baseline-mvp1.md` | Method and the results template + CI smoke |
| `infra/prod/Caddyfile`, `docker-compose.prod.yml`, `backend.Dockerfile` | Health probe follows actuator to port 8081 |

**Request trace — one measured tile:**
1. k6 VU calls GraphQL `viewerPageUrl` (`name:graphql`) → gets a signed URL.
2. k6 `GET /api/viewer/tiles/{session}/{page}?exp&sig` (`name:tile`) → `SecureTileController.getTile`.
3. `SecureTileService.getTile` starts a `Timer.Sample`, runs the D6 checks, then `timeStorageFetch` (MinIO) and `timeWatermark` (Java2D).
4. Success records `secureleaf.tile.bytes` and stops the sample with `outcome=ok`; any thrown exception stops it with the mapped outcome and rethrows.
5. Prometheus scrapes `/actuator/prometheus` every 5 s; Grafana runs `histogram_quantile(0.95, …)` over the buckets.

---

## 6. Design decisions and trade-offs

### Decision: pull-based Prometheus + Grafana
- **Alternatives considered:** push to a hosted service (Datadog/New Relic); logs-only analysis; Spring's built-in `/actuator/metrics` alone.
- **Why we chose this:** open source, free, ARM64 images, the de-facto standard interviewers know; the raw `/actuator/metrics` endpoint has no history or percentiles.
- **What we gave up:** two more containers to run locally (256 MB each) and PromQL to learn.
- **When we would revisit:** two or more backend instances (service discovery) or when alerting is needed (Alertmanager).

### Decision: time inside `SecureTileService`, not in an HTTP filter
- **Alternatives:** Spring's automatic `http.server.requests` metric; a servlet filter.
- **Why:** we need to split render vs storage and tag by *business* outcome (superseded vs forbidden are both 4xx to a filter). Spring's automatic metric is still there for HTTP-level comparison.
- **What we gave up:** the timer excludes filter chain time (JWT parsing, serialization of the body), so it slightly under-reports vs what the client sees; k6's own measurement is the end-to-end truth.

### Decision: active-sessions gauge reads Postgres, not Redis
- **Alternatives:** count `viewer:active:*` keys with `SCAN`; an in-memory `AtomicInteger`.
- **Why:** an indexed count is cheap, correct across restarts and easy to test; `SCAN` walks the keyspace (which also holds thousands of single-use tile signatures) on every scrape; an in-memory counter drifts on crashes and breaks with two instances.
- **What we gave up:** lapsed-but-unswept leases (≤ 60 s) still count, so it slightly over-reports.
- **Revisit:** if the scrape query shows up in Postgres load — cache it for 15 s.

### Decision: seeder drives the real processing pipeline, job created already claimed
- **Alternatives:** a `pg_dump`-style fixture; generating tile PNGs directly; leaving the job QUEUED for the worker.
- **Why:** running the real `DocumentProcessingService` means the tiles are exactly what an upload produces. Creating the job as PROCESSING avoids a race in integration tests, where a second cached Spring context's worker could steal a QUEUED job whose PDF lives in the *first* context's in-memory storage.
- **What we gave up:** the seeder needs the app's whole context (fine — it's a Spring profile), and the first start takes a few seconds longer.

### Decision: one shared BCrypt hash for all seeded buyers
- **Why:** BCrypt is deliberately ~100 ms; 500 separate hashes would add nearly a minute for zero benefit since the password is identical anyway.
- **Cost:** login in the load test exercises BCrypt verification only, which is realistic (verification cost is per attempt, not per stored hash).

### Decision: prod management port 8081, health moves with it
- **Alternative:** keep one port and just don't proxy `/actuator/prometheus` in Caddy.
- **Why we chose the spec's separate port:** defence in depth — a proxy misconfiguration can't expose what isn't reachable on a published port.
- **What we gave up:** the container healthcheck, the Caddy `/actuator/health` route and the Dockerfile healthcheck all had to follow (done; the Dockerfile tries 8081 then 8080). Not exercised against a real Oracle box in this phase — the first prod deploy after this change should be watched.

---

## 7. Interview questions

### Beginner
**Q: What's the difference between metrics, logs and traces?**
A: Metrics are numbers sampled over time — request rate, latency percentiles, heap size — cheap to store and great for dashboards and alerts. Logs are individual events with context, good for "what happened to this request". Traces follow one request across services to show where time went. We had logs from Phase 9; this phase adds metrics; traces are a later stretch.

**Q: Why does the dashboard show p95 and p99 instead of the average?**
A: Because the average hides the tail. If one request in a hundred takes two seconds the average barely moves but a reader flipping pages will hit it constantly. p95/p99 say what the slow users actually feel.

**Q: What does Prometheus do, and how does it get the numbers?**
A: It's a time-series database that scrapes — pulls — `/actuator/prometheus` from the backend every few seconds and stores the values. Grafana then queries it to draw graphs.

### Intermediate
**Q: Why did you use a histogram for tile latency?**
A: A histogram counts requests per latency bucket, and Prometheus estimates percentiles from those counters with `histogram_quantile`. Without `publishPercentileHistogram()` you only get count, sum and max — an average but no p99. Buckets also aggregate across instances, which pre-computed percentiles don't.

**Q: Why tag the timer by outcome?**
A: Because rejected requests (bad signature) are fast and successful ones are slow; mixed together the percentile describes nothing. Tagging by `ok / forbidden / superseded / not_found / error` also gives the requests-per-second-by-outcome panel, where a wall of `forbidden` at load is a finding. It's safe because it's low-cardinality; I would never tag by user id.

**Q: Explain Little's Law and how it applies to Tomcat.**
A: Concurrency equals throughput times latency. Busy threads ≈ requests per second × seconds per request. With ~90 ms tiles, 100 tiles/s keeps ~9 threads busy — but tile rendering burns CPU, so on two cores the CPU saturates long before 200 threads do. The busy-vs-max panel shows when a queue starts to form.

**Q: How do you keep the seeder from wrecking production?**
A: It only exists under the `loadtest` Spring profile, and in `seed()` it checks `environment.acceptsProfiles("prod")` and throws before touching anything; a unit test pins that. The accounts have a known published password, so it must never run there.

### Advanced / follow-up probes
**Q: What is coordinated omission and does your k6 script suffer from it?**
A: When the load generator waits for a slow response before sending the next, it silently omits the requests it would have sent during the stall, so the worst latencies are under-recorded. `ramping-vus` is a closed model, so yes, at saturation it under-reports. For readers that is a fair model — real people also wait for the page — but for a "requests per second the server survives" claim you switch to `ramping-arrival-rate`. I documented it rather than pretending the number is perfect.

**Q: Why not let metrics sit on the normal port behind auth?**
A: Prometheus doesn't carry a JWT; adding a static token is more secrets to manage. Moving actuator to a private port makes network position the control: Docker doesn't publish 8081 and Caddy forwards only `/actuator/health`. It's the same principle as not exposing your database, and `ManagementPortIT` checks that the app port doesn't serve the metrics.

**Q: A Prometheus series per tag combination — how could this phase have gone wrong?**
A: Tagging with `userId`, `sessionId` or the URL would create a new time series per value — thousands of series, memory blow-up in Prometheus and slow queries. Keep tags to a small fixed set, and put the high-cardinality detail in logs with the correlation id.

**Q: How would you know the smoke-test numbers are not the capacity?**
A: The report says so, and the setup shows why: k6, backend, DB on one 4-vCPU runner and in-memory storage instead of MinIO. Capacity numbers need target hardware, load generator elsewhere, warm-up, repeated runs and the same method every time.

### "Tell me about a bug you fixed"
**Q: Tell me about a bug you hit while building the load-test harness.**
A: The seeder's first integration test failed on startup with a duplicate-key error on `categories_pkey`. The Flyway seed migration inserts categories with explicit ids, which leaves the Postgres sequence behind, so the seeder's `INSERT` of its own "load test" category collided. I only found it because the seeder ran against a real database rather than a mock. The fix was to reuse an existing category instead of inventing one. The lesson: seeding data with explicit ids and later inserting by sequence is a classic trap — and idempotent seeders need to survive half-finished previous runs too (a second bug: a run that died after saving the product left it without a document, so the next run waited forever until I made it detect and finish that).

---

## 8. Gotchas and bugs we hit

| Symptom | Root cause | Fix | Lesson |
|---|---|---|---|
| `/actuator/prometheus` returned 404 in tests | `@SpringBootTest` turns metric exporters off by default | `management.prometheus.metrics.export.enabled=true` in `application-test.yml` (no `@AutoConfigureObservability`, so no extra Spring context) | Test slices differ from prod defaults; a per-exporter property beats the global test default |
| Seeder: `duplicate key … categories_pkey` | Migration seeds rows with explicit ids; the sequence isn't advanced | Reuse an existing category | Explicit-id seeds and sequence inserts don't mix |
| Seeder waited for a document that never appeared (`NoSuchElementException`) | A prior failed run left a product without its document version | Detect the missing version and provision it | Idempotency must cover *partial* previous runs, not just complete ones |
| Seeded data vanished before the test asserted | `AbstractIntegrationTest` truncates every table before each test | The test calls `seeder.seed()` itself; the startup run is incidental | Know your base-class lifecycle |
| Risk: another Spring context's job worker stealing the seeder's job | Cached contexts share one Postgres but not in-memory storage | Job created in `PROCESSING` and processed directly; worker disabled for the secondary context | Shared DB + per-context state = flaky tests (same class of bug as `ProductRecoveryIT`) |
| `k6 inspect` failed in CI-like use | `open(users.csv)` ran at init and threw when the file was missing | Wrapped in try/catch; `setup()` fails real runs with a clear message | Init code runs for `inspect` too |
| Overall `/actuator/health` DOWN in the smoke run | Mail health indicator, no SMTP server | Not a bug here (prod disables that indicator); readiness was UP | Read *which* component is down |
| `pkill -f` killed my own shell | The pattern matched the shell's own command line | `pgrep -f "[x]"` trick / a script file | Pattern-kill can hit yourself |

---

## 9. New vocabulary

| Term | One-line meaning |
|---|---|
| Observability | Being able to answer "what is the system doing and why" from its outputs |
| Metric / Timer / Gauge / DistributionSummary | Micrometer types: a number over time / duration + count / current value / distribution of sizes |
| Scrape | Prometheus pulling `/actuator/prometheus` on an interval |
| PromQL | Prometheus' query language (`rate()`, `histogram_quantile()`) |
| Histogram bucket | Counter of observations ≤ a boundary; the basis of percentile estimates |
| Percentile (p50/p95/p99) | The value below which that % of observations fall |
| Cardinality | Number of distinct tag-value combinations (each is a series) |
| RED / USE | Rate-Errors-Duration for services / Utilisation-Saturation-Errors for resources |
| Little's Law | Concurrency = throughput × latency |
| Coordinated omission | A closed-loop tester under-recording latency because it slows down with the server |
| Load / stress / soak test | Expected peak / beyond peak / long duration |
| VU (virtual user) | One simulated user in k6 |
| Think time | Pause modelling a human reading |
| Provisioning (Grafana) | Loading datasources/dashboards from files at start-up |
| Management port | Separate port for actuator endpoints |

---

## 10. If I had to defend this in a code review

- **Strongest:** the dashboard can't silently rot (a test ties it to the meter constants), the meters answer specific questions (where does a tile's time go?), and every limit of the numbers is written next to them.
- **Strongest:** the seeder reuses the real pipeline and is idempotent, guarded and tested, so "500 realistic buyers" is one command.
- **Weakest:** the smoke baseline used in-memory storage because the MinIO image couldn't be pulled in the run, so the render-vs-storage split is only half-informative until the owner's real-hardware run; and the prod management-port move was verified by integration test and Caddy config validation, not by a live prod deploy.
- **Weakest:** no host CPU metric is scraped (only JVM metrics), so CPU saturation is read from `docker stats`; the fix is adding node-exporter/cAdvisor to the monitoring profile.
- **Would add next:** `ramping-arrival-rate` scenario for the open model, alert rules on p99 and busy-thread saturation, OpenTelemetry traces linking a tile request to its DB/MinIO calls.
