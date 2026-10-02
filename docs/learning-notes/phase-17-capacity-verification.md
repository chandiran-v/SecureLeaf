# Phase 17 — 5,000-viewer capacity verification (MVP2 finish line)

> **Status:** Harness done and proven at CI scale; the full-scale run is the owner's (`TODO(owner-run)`)
> **Built:** 2026-10-02
> **Requirement IDs covered:** MVP2-07 (and the traceability of MVP2-01…06 in [`docs/release-mvp2.md`](../release-mvp2.md))
> **Commits:** see `git log --grep "Phase 17"` on branch `auto/issue-17`

---

## 1. What we built, in plain English

Phases 11–16 each *claimed* to make the viewer faster or safer. This phase is the one that asks "so, did it
work, and how many readers can we now serve?" Nothing in the app's behaviour changed. What was built is the
**experiment** and the **paperwork around it**: a load-test script that ramps from 500 readers up to 5,000 and
holds each level for ten minutes, a way to run that load from several machines at once, a log of every
tuning change we tried (with whether it helped), a report that says what we know and what we don't, and a
table that maps every MVP2 requirement to the evidence for it.

A real 5,000-reader test needs real hardware, which only the owner has. So the automated run did what fits in a
CI machine: it ran the *same* test scaled down (20 → 50 → 100 readers) to prove the scripts, thresholds and
dashboard work, recorded the numbers clearly labelled "not capacity evidence", and made the real run a
single command.

**Before this phase:** the target "5,000+" in `requirements.md` was a hope. There was a Phase 10 harness but its
full baseline had never been run, and none of the per-phase before/after pages were filled.
**After this phase:** there is a repeatable capacity test with pass/fail criteria per step, a nightly smoke
guard, a dashboard row for capacity, and — the real discovery — a measured model showing *why* the CPU
bottleneck, not the software changes, decides whether 5,000 is reachable.

---

## 2. Why it matters

Performance work without a final measurement is a story, not a result. Interviewers ask "how did you know it
got better?" and "what's your bottleneck now?". This phase is the answer: a method (matrix with thresholds),
evidence (CI-scale runs, a tuning log) and honesty about what is not proven (no real-hardware numbers; one of the
four capacity phases, libvips, was never built).

What would break if we skipped it: we would ship "supports 5,000 viewers" on faith, and the first time it
mattered we would learn the real number from users. The CI-scale run alone already found two things nobody
had written down: the cache hit rate under a realistic mix is ~5 % (not 40 %), and with the benchmark's
think time 5,000 readers need ~130 CPU cores of rendering.

---

## 3. New concepts introduced

### 3.1 Capacity planning vs benchmarking

**What it is:** A *benchmark* measures how fast something is (one number, controlled conditions). A *capacity
test* finds how much **load** a whole system can carry while meeting an agreed standard, and which part gives out
first.

**The analogy:** A benchmark is a car's 0–60 time. A capacity test is "how many cars can use this motorway before
the journey time passes 20 minutes?", and then finding that the real limit is the toll booth, not the road.

**Why we needed it here:** MVP2-07 is a statement about *concurrent viewers*, not about renders per second. Only a
load that behaves like readers (log in, open a book, read pages with think time, heartbeat) can answer it.

**How it works:** pass criteria per load step (p95 < 500 ms, p99 < 1 s, errors < 0.5 %, no heap growth); the step
where it first fails *is* the capacity.

**In our code:** `loadtest/capacity.js:90-91` generates the thresholds for every hold:
```js
thresholds[`http_req_duration{name:tile,step:hold-${target}}`] = [`p(95)<${TILE_P95_MS}`, `p(99)<${TILE_P99_MS}`];
thresholds[`tile_errors{step:hold-${target}}`] = [`rate<${MAX_ERROR_RATE}`];
```

**What breaks without it:** a single "the test passed" at the end hides *which* step failed; a benchmark at one
load hides the knee in the curve.

### 3.2 Finding the bottleneck: the USE method

**What it is:** For every resource (CPU, memory, disk, network, pools) check **U**tilisation (how busy),
**S**aturation (is work queued waiting) and **E**rrors. The bottleneck is the resource at ~100 % utilisation with
a queue in front of it.

**The analogy:** A restaurant: the kitchen at 100 % with tickets piling up is the bottleneck, however many
waiters stand idle.

**Why we needed it here:** The CI-scale run: machine CPU 99 %, render queue up to 72 deep, queue wait 2.86 s of
a 3.02 s p95, GC pauses 8 ms/s, request threads idle. One resource is saturated; the others are not. That is the
whole analysis, in four numbers.

**In our code:** the Grafana "Capacity" row (`infra/grafana/dashboards/secureleaf-viewer.json`, panels 14–18)
puts utilisation (CPU), saturation (render queue) and errors next to each other on purpose.

**What breaks without it:** guessing. Tuning GC or Tomcat threads would have been a waste: neither was the problem.

### 3.3 Amdahl's law and why the next bottleneck moves

**What it is:** If a fraction `p` of the work is sped up by a factor `s`, the overall speed-up is
`1 / ((1 − p) + p/s)`. Speeding up one part has diminishing returns because the *other* parts remain.

**The analogy:** Making the oven twice as fast helps only until the queue at the cash register becomes the limit.

**Why we needed it here:** The render is ~all the CPU now, so libvips (if it is 4× cheaper) would be worth almost
4×. But after that, Postgres connections (10 in the pool), Redis round trips, the NIC (350 KB × tiles/s) and MinIO
become the next limits. The report lists them so the *next* run knows where to look.

**What breaks without it:** celebrating "5× faster renders" and being surprised when throughput rises 2×.

### 3.4 Little's Law and the think-time lesson

**What it is:** In a stable system, `concurrent users = throughput × time each spends in the system`. For us:
`N = X × (Z + R)` where `Z` is think time and `R` response time; so `X ≈ N / (Z + R)`.

**Why it matters here:** It turns "5,000 viewers" into work: at 5 s think time that is ~1,000 tiles/s; at 30 s it
is ~167/s. Multiply by the measured CPU per tile (~0.13 core-seconds) and you get **130 cores vs 22 cores**. The same
"5,000 viewers" is a six-fold difference in hardware depending on how people read. That is the most important
sentence in the capacity report.

**In our code:** `docs/perf/capacity-report-mvp2.md` §5 (the sizing table).

**What breaks without it:** A capacity target quoted without think time is meaningless; two teams can both "support
5,000" and differ by 6× in the load they handled.

### 3.5 Connection-pool sizing maths

**What it is:** A pool of `P` database connections serves queries concurrently. The connections *busy* on average
are `queries/s × seconds per query` (Little again). Too few: requests wait for a connection. Too many: the database
thrashes, and the total (pool size × backend instances) can exceed Postgres's `max_connections` and be refused.

**The analogy:** Checkout tills in a shop. More tills help until there are more tills than the stockroom can feed.

**Why we needed it here:** D3 asks to tune Hikari against `max_connections`. We made the default explicit
(`backend/src/main/resources/application.yml:14`, `maximum-pool-size: ${DB_POOL_MAX:10}`) and wrote the
arithmetic in the tuning log: ~5 queries × 2 ms per tile ≈ 10 ms of connection time per tile → ~10 busy
connections at 1,000 tiles/s. So 10 is borderline *only* at the extreme case; the signal to watch is
`hikaricp_connections_pending`, not a guess.

**What breaks without it:** Raising the pool "because it's slow" when the real bottleneck is CPU, and then
taking Postgres down with 400 connections.

### 3.6 GC choice: G1 vs ZGC

**What it is:** The garbage collector decides when the JVM pauses to reclaim memory. G1 balances throughput and
pauses; ZGC minimises pauses at the cost of some throughput (more concurrent work).

**Why we needed it here:** We *tested* it instead of reading a blog. ZGC took GC pause from 7 ms/s to 0, and
cost **22 % of throughput** and +28 % p95 on a CPU-saturated box (tuning log entry 4). When the CPU is the
bottleneck, a collector that burns extra CPU to avoid pauses loses. G1 stays (`infra/docker/backend.Dockerfile:43`).

**What breaks without it:** Cargo-cult "ZGC is modern, use it" making the saturated resource *worse*.

### 3.7 Distributed load generation (and measuring the load generator)

**What it is:** One test machine may itself run out of CPU or network before the server does, and the symptom
looks identical to a slow server. So split the virtual users across several generators and watch the generators.

**In our code:** `loadtest/docker-compose.k6.yml` (four generators, each runs `ceil(target / N)` VUs and its own
slice of `users.csv`, `capacity.js:248-250`) and `loadtest/run-capacity.sh`.

**What breaks without it:** An invalid capacity number. A cheap sanity check: compare k6's tile latency with the
backend's own histogram; a large gap means the time is lost on the generator or network.

### 3.8 Memory is a correctness knob: heap vs render concurrency

**What it is:** The render pool bounds *concurrent* renders; each in-flight render holds several full-size images
in the heap. So `heap ≥ pool size × per-render working set × safety`.

**Why it mattered here:** `-Xmx1g` threw `OutOfMemoryError` 3 times at 4 threads and 15 times at 8 threads (the
client got "Failed to render watermark"); `-Xmx2g` threw none and was no faster. (Tuning log entry 3.)

### 3.9 Detecting a leak: look at the floor, not the saw

**What it is:** Heap used is a sawtooth (allocate, collect, repeat), so its slope means nothing. The heap *live after
a collection* only rises if something is retained. And in-flight tiles inflate single readings, so we use a rolling
**minimum** of that gauge.

**In our code:** `loadtest/heap-trend.js:17` `slope()` (least squares) and `:36` `floor()` (rolling minimum),
unit-tested in `loadtest/heap-trend.test.js`.

**What breaks without it:** A "no heap growth" criterion judged by eye on a sawtooth chart. (Our own CI-scale run
shows the gauge needs minutes of data: see §8.)

---

## 4. Best practices applied

| Practice | What we did | Why it matters | Where |
|---|---|---|---|
| One variable per run, repeat the control | Every tuning entry is a single change with a repeated baseline | A ±5 % spread meant effects of 2–3 % could not be claimed | `docs/perf/tuning-log.md` ground rules |
| Pair latency with error rate | Thresholds on p95/p99 *and* errors, per hold | A service that fails instantly looks fast; this caught two invalid runs | `loadtest/capacity.js:90-91` |
| Exclude intended rejections | `http.setResponseCallback(expectedStatuses(2xx/3xx, 429))` | A rate-limited scraper must not count as an outage | `loadtest/capacity.js:62` |
| Label evidence honestly | "CI-scale, not capacity evidence" everywhere; `TODO(owner-run)` for unfilled cells | Interviewers (and the owner) trust a report that names its gaps | `docs/perf/ci-scale-results.md`, `capacity-report-mvp2.md` |
| Deterministic reader mix | Persona = position in a block of ten users, global across generators | The 70/20/10 mix holds across 4 generators and across runs | `loadtest/capacity.js:53,250` |
| Tag by step, threshold by step | `step=hold-N` tag from elapsed time | Per-step verdicts from one continuous run | `loadtest/capacity.js:72-91` |
| Dashboard that can't drift | New panels' series checked by `GrafanaDashboardTest` | A renamed meter fails the build, not a load test | `backend/src/test/.../GrafanaDashboardTest.java` |
| Regression guard in CI | `smoke.js` + `docs/ci/perf-smoke.yml.example` | Slowdowns are caught the day they are introduced | `loadtest/smoke.js` |

---

## 5. What does what — file map

| File | Responsibility |
|---|---|
| `loadtest/capacity.js` | The matrix: ramps, holds, reader mix, per-hold thresholds, distributed slicing |
| `loadtest/smoke.js` | 20 VUs / 60 s regression guard, p95 < 500 ms |
| `loadtest/heap-trend.js` (+ `.test.js`) | Leak check from Prometheus: rolling-minimum floor + least-squares slope |
| `loadtest/docker-compose.k6.yml` | Four k6 generators, each its own VU and user slice |
| `loadtest/run-capacity.sh` | One-command owner run: generators → exit codes → heap-trend per hold |
| `docs/ci/perf-smoke.yml.example` | The nightly smoke job (to be copied into `.github/workflows/` by the owner) |
| `infra/grafana/dashboards/secureleaf-viewer.json` (panels 13–18) | The "Capacity" row |
| `docs/perf/tuning-log.md` | Every change → hypothesis → result, plus the D3 checklist and pool maths |
| `docs/perf/ci-scale-results.md` | The labelled CI-scale numbers |
| `docs/perf/capacity-report-mvp2.md` | Method, hardware, results, attribution, bottleneck, next steps, cost |
| `docs/release-mvp2.md` | MVP2-01…07 → evidence |
| `backend/src/main/resources/application.yml:14` | `DB_POOL_MAX` made explicit |

**Request trace — one capacity run:**
1. `run-capacity.sh` → `docker compose up` starts 4 `capacity.js` generators, each with `GENERATOR_INDEX` →
2. each VU logs in, starts a viewer session, reads pages as its persona, heartbeating every 15 s →
3. each request is tagged `step=…` from elapsed time since `setup()` → k6 evaluates per-hold thresholds →
4. Prometheus scrapes the backend; the Capacity row shows VUs vs p95, errors, CPU, threads, hit rate →
5. `heap-trend.js` fits the post-GC floor per hold → exit code and the report cells are filled in.

---

## 6. Design decisions and trade-offs

### Decision: tag by elapsed time inside one scenario, not one k6 scenario per step
- **Alternatives considered:** a scenario per step with `startTime` offsets.
- **Why we chose this:** separate scenarios have *separate* VU pools, so 5,000 would be the sum of steps, not a ramp
  of the same readers. One `ramping-vus` scenario plus a `step` tag gives true ramps and per-hold thresholds.
- **What we gave up:** the schedule is duplicated in the script (`SCHEDULE`) and relies on the clocks agreeing to
  within a few seconds across generators.
- **When we would revisit:** if generators on separate machines drift; then start them against a common wall-clock.

### Decision: keep a closed-loop model (`ramping-vus`)
- **Alternatives considered:** `ramping-arrival-rate` (open model).
- **Why:** readers *are* closed: they wait for the page. An open model would also be the right tool for an edge
  capacity claim, but would mis-model the readers.
- **What we gave up:** coordinated omission: a stalled server gets *less* load, hiding tail latency.
- **When we would revisit:** if we publish a requests/second number to a third party.

### Decision: leave the default render pool size alone although a bigger pool "won" at CI scale
- **Alternatives considered:** change `size` to 2× CPUs because p95 improved 14 %.
- **Why we didn't:** 14 % is barely above the noise, per-tile render time doubled (threads time-share cores),
  the likely reason is the host being shared with k6/Postgres, and bigger pools need more heap (the OOM). The
  owner re-tests on a dedicated host.
- **What we gave up:** possibly 6 % throughput on a real server.
- **When we would revisit:** the full run on dedicated hardware.

### Decision: record failing runs and inconclusive checks as they are
- **Why:** the heap-trend check *failed* at CI scale (7.7 MB/min vs a 5 limit) and is documented as inconclusive
  (one-minute holds, warm-up in the window, 350 KB tiles in flight). Loosening the limit until green would have been
  the dishonest alternative.

### Decision: ship the report with `TODO(owner-run)` cells and a cost *estimate*, not invented numbers
- **Alternatives considered:** extrapolate CI-scale numbers to 5,000 VUs.
- **Why not:** a saturated 4-vCPU shared box does not extrapolate; and a model (§3.4) is more honest than a fake table.
- **What we gave up:** the headline result.
- **When we would revisit:** the owner's run.

---

## 7. Interview questions

### Beginner
**Q: What is the difference between a load test and a stress test?**
A: A load test checks the system at the load we *expect* and asks "does it meet the targets?". A stress test keeps
raising the load until it breaks, to learn how it fails. Our matrix is a load test at four levels; the point at
which a step fails is where the stress starts.

**Q: Why do you look at p95 and p99 instead of the average?**
A: Averages hide the slow tail, and the tail is what users feel. In our CI-scale run the average tile time at 100
readers was 2.4 s while p99 was 3.1 s; but earlier in the ramp a few slow requests would vanish in an average.
Also a few instant failures *lower* an average, so we pair latency with the error rate.

**Q: What is "think time" and why does it matter?**
A: The pause a real user takes between actions: reading a page. A load test with no think time is a hammer, not
readers. It also sets throughput: concurrent users ≈ throughput × (think + response time).

### Intermediate
**Q: Your test passed with 0 % errors but 14 ms p95. Is that good?**
A: Not necessarily. That actually happened to us once at "50 % errors, 14 ms p95": the storage had been wiped by a
restart so every tile failed in microseconds. I always read latency *with* the error rate; that is why the thresholds
pair them.

**Q: How do you find a bottleneck?**
A: USE method: for every resource, utilisation, saturation, errors. The resource that is ~100 % busy with a queue
in front of it is the bottleneck. For us that was CPU (99 %), the render queue (up to 72) and queue wait (2.86 s of a
3.02 s p95), while GC pauses were 8 ms/s and request threads were idle.

**Q: How would you size a database connection pool?**
A: Little's Law: busy connections ≈ queries per second × seconds per query. Then cap by the database: pool size ×
instances must stay below `max_connections` with headroom. And I check `connections_pending` instead of guessing.
Bigger is not better: past what the DB can run concurrently it only moves the queue.

**Q: Why did you keep G1 when ZGC has near-zero pauses?**
A: I measured it. On a CPU-bound service ZGC cut GC pauses from 7 ms/s to zero but lost 22 % of throughput and added
28 % to p95, because its concurrent work competes for the very CPU that was saturated. Pauses were never the problem.

### Advanced / follow-up probes
**Q: How do you know your load generator isn't the bottleneck?**
A: Measure it: CPU per generator (above ~80 % invalidates the run), `http_req_blocked`/`connecting`, dropped
iterations, and compare k6's latency with the server's own histogram; a gap means time is lost outside the app.
Then split the VUs across several generators on separate machines, which is what `docker-compose.k6.yml` is for.

**Q: What is coordinated omission and does it affect you?**
A: In a closed-loop test, a user waits for a slow response before sending the next request, so the test sends *less*
load exactly when the server struggles and under-reports the damage. For readers that matches reality, so the
model is right, but I'd use an open model (arrival rate) for any requests-per-second claim.

**Q: How do you present "from 500 to 5,000" in an interview?**
A: Honestly, as a method and a model, not a trophy. "I built a matrix with per-step criteria and distributed
generators; at CI scale I found CPU in the render saturates at ~29 tiles/s on 4 shared cores, which is ~0.13
core-seconds per tile; by Little's Law 5,000 readers at 5 s think time is ~130 cores but at a realistic 30 s it is
~22, so the claim depends on think time more than code. The changes: rate limiting protects the ceiling; the render
pool makes overload survivable; the cache helps by the real hit rate, ~5 % here, not the 40 % we assumed; libvips,
the biggest lever, is not built yet." That shows the numbers, the reasoning, and what is unproven.

**Q: A tuning change improved p95 by 14 %. Do you ship it?**
A: Not on that alone. I repeat the control to know the noise (here ±5 %), check *why* it improved (here, probably
the shared host), look at the side-effects (per-tile time doubled, more memory), and re-test on the target
hardware. Then record it either way in the tuning log.

### "Tell me about a bug you fixed"
**Q: Tell me about a time a test result was wrong.**
A: *Situation:* a re-run of the capacity test showed p95 of 14 ms, far better than before. *What was wrong:* the error
rate was 50 %; the JVM restart had wiped the in-memory object store while the database still listed the pages, so
every tile 500'd almost instantly. A second time, running `mvn test` while the app was up made DevTools restart it in
place. *Fix:* a fresh database and JVM per run and DevTools restart disabled, plus the habit of reading error rate
beside latency. *Lesson:* a fast failure is the fastest response there is; never trust a latency number alone.

---

## 8. Gotchas and bugs we hit

| Symptom | Root cause | Fix | Lesson |
|---|---|---|---|
| p95 14 ms, 50 % errors | JVM restart wiped in-memory storage, DB rows remained | Fresh DB + JVM per run | Latency is meaningless without errors |
| Same, again, mid-chain | `./mvnw test` while the app ran → Spring DevTools restarted it | `-Dspring.devtools.restart.enabled=false` | Anything touching `target/classes` can restart a DevTools app |
| `OutOfMemoryError` at 1 GB heap (3× at 4 threads, 15× at 8) | Each in-flight render holds several full-size images | 2 GB heap; rule: size heap with pool size | Concurrency × working set = memory |
| `tomcat_threads_busy` = −1 | Virtual threads: no platform request threads to count | Capacity row uses render pool/queue | A metric can silently stop meaning anything after an architecture change |
| ZGC run: heap-trend "0.0 MB" | `jvm_gc_live_data_size_bytes` is not published by ZGC | Document: heap-trend needs G1 | Check a metric exists for *your* configuration |
| heap-trend FAIL at CI scale (7.7 MB/min) | 1-minute holds + warm-up + 350 KB tiles in flight swamp the signal | Rolling-minimum floor; documented as inconclusive, not loosened | A leak check needs minutes; don't tune the threshold to pass |
| Dev profile: 4.5 M log lines per run | `dev` logs all SQL and security decisions | Quiet-logging flags in README and the CI example | Remove variables before measuring (effect was only 2–3 % here) |
| YAML example failed to parse | Unquoted `:` in a step name | Reworded the name | Validate YAML before committing |
| `pkill -f` killed my own shell | The pattern matched the command line running pkill | `pgrep -f "[S]ecure…"` trick | Self-matching patterns |

---

## 9. New vocabulary

| Term | One-line meaning |
|---|---|
| **Capacity test** | Finds the load a whole system carries within agreed targets, and what gives out first |
| **Benchmark** | Measures one operation's speed under controlled conditions |
| **Hold** | The steady stretch at each load level, after the ramp, where criteria are judged |
| **USE method** | Utilisation, Saturation, Errors, checked per resource |
| **Amdahl's law** | Speeding up one part is limited by the parts you didn't speed up |
| **Little's Law** | Concurrent users = throughput × time in system |
| **Think time** | Pause between a user's actions; sets throughput per user |
| **Open vs closed load model** | Open: arrivals independent of responses; closed: a user waits for the reply |
| **Coordinated omission** | A closed-loop test sends less load while the server stalls, hiding latency |
| **Load generator** | The machine(s) producing the test traffic: also a possible bottleneck |
| **Live data after GC** | Heap still referenced after a collection; the floor whose trend reveals leaks |
| **G1 / ZGC** | Throughput-balanced vs ultra-low-pause garbage collectors |
| **Persona** | A fixed reader behaviour (sequential, back-navigation, mobile) assigned per virtual user |
| **Regression guard** | A small, fast test that fails when something gets slower |

---

## 10. If I had to defend this in a code review

- **Strongest:** the criteria are per hold and pair latency with errors, the run is distributable, every tuning
  change is recorded with its result (including the ones that didn't help), and the report refuses to invent
  numbers it doesn't have.
- **A finding worth defending:** the measured model (0.13 core-seconds per tile, Little's Law) shows the 5,000 target
  is mostly a statement about think time and CPU, which changes the conversation about MVP2-07.
- **Weakest point:** there is still **no real-hardware number** at all: the Phase 10 baseline and the per-phase
  before/afters were never run, so attribution (§4 of the report) is mechanism, not measurement, and the CPU cost
  per tile is from a shared x86 runner, not the ARM production box. The fix is the owner's run (`run-capacity.sh` ×3,
  including a "before" at commit `2e093c7`), then replacing the model rows with measurements. The second weakest
  point: MVP2-01 (libvips) is not built, and it is the largest lever.
