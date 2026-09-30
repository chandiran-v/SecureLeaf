# SecureLeaf load tests (Phase 10)

k6 scenarios that model **readers**: each virtual user (VU) logs in, opens a book, sends a
heartbeat every 15 s and reads pages one after another with a think time between them. The point is
to *measure* the viewer's hot path (tile render) — see [`docs/perf/README.md`](../docs/perf/README.md)
for method and [`docs/perf/baseline-mvp1.md`](../docs/perf/baseline-mvp1.md) for results.

No local k6 install is needed — use the Docker image.

## 1. Start the stack

```bash
# infra (Postgres, Redis, MinIO) + Prometheus/Grafana
docker compose -f infra/docker-compose.yml --profile monitoring up -d

# backend on the host, with the loadtest profile: it seeds data on startup
cd backend
SPRING_PROFILES_ACTIVE=dev,loadtest LOADTEST_USERS=500 ./mvnw spring-boot:run
```

`LoadTestSeeder` (profile `loadtest`, refuses to run under `prod`) creates
`loadtest-buyer-0001@secureleaf.test` … `-0500@…`, one 12-page book from a bundled PDF, an ACTIVE
entitlement for each buyer, and writes their credentials to **`loadtest/users.csv`** (git-ignored).
It is idempotent: restarting creates nothing new. Every account shares the password
`LoadTest#2026` (override with `LOADTEST_PASSWORD`). Wait for the log line
`Load-test seed done: …`.

Open Grafana at <http://localhost:3001> (admin / admin) → **SecureLeaf — Viewer**.

## 2. Run k6

```bash
docker run --rm -i --add-host=host.docker.internal:host-gateway \
  -v "$PWD/loadtest:/loadtest" \
  -e BASE_URL=http://host.docker.internal:8080 \
  -e VUS=50,200,500 -e STAGE_DURATION=2m \
  grafana/k6 run /loadtest/viewer.js
```

| Env var | Default | Meaning |
|---|---|---|
| `BASE_URL` | `http://host.docker.internal:8080` | Backend to hit |
| `USERS_CSV` | `/loadtest/users.csv` | Credentials written by the seeder |
| `VUS` | `50,200,500` | Comma-separated stage targets; one ramp stage per value |
| `STAGE_DURATION` | `1m` | Length of each stage |
| `THINK_TIME` | `5` | Seconds a reader "reads" one page |
| `HEARTBEAT_EVERY` | `15` | Seconds between session heartbeats |
| `BACK_NAV_RATE` | `0.1` | Chance of flipping back one page after a page |
| `TILE_P95_MS` | `500` | Threshold for p95 of tile requests |

**Thresholds (the run fails if broken):** `http_req_duration{name:tile}` p(95) < 500 ms and
`http_req_failed` < 1 %.

`VUS` must not exceed the number of users in the CSV (one buyer = one VU; the same buyer twice would
supersede its own session). Seed with `LOADTEST_USERS` ≥ your peak.

## Smoke test (what CI-style runs use)

```bash
docker run --rm -i --add-host=host.docker.internal:host-gateway -v "$PWD/loadtest:/loadtest" \
  -e VUS=20,20 -e STAGE_DURATION=30s -e THINK_TIME=1 grafana/k6 run /loadtest/viewer.js
```

A smoke test proves the harness works. It is **not** a capacity result.

## Check the script without running it

```bash
docker run --rm -v "$PWD/loadtest:/loadtest" grafana/k6 inspect /loadtest/viewer.js   # or: node --check
```

## Load vs stress vs soak (which run is which)

- **Load** — expected peak (`VUS=50,200,500`): does it meet the thresholds?
- **Stress** — keep raising VUs until it breaks (`VUS=500,1000,2000`): *how* does it fail?
- **Soak** — moderate VUs for hours (`VUS=200,200 STAGE_DURATION=2h`): leaks, pool exhaustion.

## Caveat: closed model

k6's `ramping-vus` is a *closed* workload — a VU waits for its response before the next request, so
when the server slows down, the test automatically sends fewer requests (**coordinated omission**).
Reader behaviour is closed anyway (people wait for the page), which is why this executor fits;
for a raw request-rate test use `ramping-arrival-rate`. See the learning note.

## Scraper scenario (Phase 11): `scraper.js`

`viewer.js` models honest readers, who must never see a 429. `scraper.js` is the opposite: **one**
buyer with a valid entitlement asks for pages in a tight loop, no think time — the bulk-ripping
attack the per-buyer limit (`ratelimit.*`, see the [learning note](../docs/learning-notes/phase-11-rate-limiting.md))
exists to slow down.

```bash
docker run --rm -i --add-host=host.docker.internal:host-gateway -v "$PWD/loadtest:/loadtest" \
  -e DURATION=60s grafana/k6 run /loadtest/scraper.js
```

| Metric | Meaning |
|---|---|
| `tile_ok` | tiles actually delivered (200) |
| `tile_limited` | tile requests answered 429 |
| `pageurl_limited` | `viewerPageUrl` calls answered `RATE_LIMITED` |

**Expected result** with the default limits (tile: burst 5, refill 2/s; page-url: burst 10, refill 4/s):
the vast majority of requests are 429, and `tile_ok` grows at about **2 per second** (about
`5 + 2 × seconds` in total) however fast the script loops. The threshold `tile_ok: rate<3` encodes that.
Watch `secureleaf_ratelimit_rejected_total{bucket="tile"}` climb on `/actuator/prometheus`, and after
10 minutes of it the backend logs `possible scraper userId=…`; `suspectedScrapers` (admin GraphQL)
lists the user.

> **Not yet measured on real hardware.** The figures above follow from the configured limits and are
> asserted by `RateLimiterTest` / `ViewerIT`; the k6 run itself has not been executed in CI. Record a
> real run in `docs/perf/` when you do one.

## Phase 12: what to expect under overload

The render pool answers `503` + `Retry-After` when saturated, and k6 counts that as a failed
request. See [`docs/perf/phase-12-render-pool.md`](../docs/perf/phase-12-render-pool.md) for the
before/after procedure and what the dashboard's *Render pool* panels show.
