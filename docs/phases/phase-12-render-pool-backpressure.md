# Phase 12 — Decoupled rendering pool + backpressure

> Spec for an unattended run. Decisions are numbered (D1…) and referenced from code comments.
> Requirements: **MVP2-03**.
> Depends on: Phases 10–11. Target branch: `feature/secure-leaf-mvp2`.

## Context

Today every tile request burns its watermark on a Tomcat servlet thread. CPU-bound rendering and I/O-bound work (storage fetch, DB) share one pool. Under load, slow renders tie up threads, and **everything** queues behind them: GraphQL, login, heartbeats. Phase 10's dashboard shows busy threads vs latency. This phase isolates rendering and adds explicit backpressure: when saturated, fail fast and cleanly instead of collapsing slowly.

## Decisions

- **D1 — Bulkhead.** A dedicated render executor sized for CPU work:
  - `render.pool.size` defaults to the number of available processors
  - bounded queue `render.pool.queue-capacity`, default 200
  - `AbortPolicy`, mapped to backpressure
  - named threads `tile-render-`

  **Platform threads, not virtual threads, for the render work itself.** Virtual threads help blocking I/O, not CPU-bound Java2D. The note explains this carefully, because it's a common interview trap.
- **D2 — Virtual threads for I/O.** Enable `spring.threads.virtual.enabled=true` (Java 21) so request handling and storage and DB I/O don't pin platform threads. Re-run the Phase 10 smoke to confirm there's no regression. Check for `synchronized` pinning in hot paths (JFR `jdk.VirtualThreadPinned`) and record what you find.
- **D3 — Asynchronous controller.** The tile endpoint returns `CompletableFuture<ResponseEntity<byte[]>>`:
  - The D6 access checks from 05A and the storage fetch run on the request (virtual) thread.
  - `supplyAsync(render, renderExecutor)` does the watermark.
  - `orTimeout(render.timeout-ms, default 5000)` → 503.
  - The rate-limit filter (Phase 11) still runs first.
- **D4 — Backpressure.** When the render queue is full, return **503 Service Unavailable** with `Retry-After: 1`, not 429: this is server overload, not client misbehaviour, and the note explains the distinction. Increment `secureleaf.render.rejected`. The frontend treats 503 like 429 (a jittered retry), at most 2 times, then shows "Busy, retrying…".
- **D5 — Metrics.** `secureleaf.render.queue.size` and `secureleaf.render.active` gauges, and a `secureleaf.render.wait` timer (time spent queued). Add panels to the Grafana dashboard.
- **D6 — Access-log write** stays after a successful render. It must not run on the render pool.

## Acceptance criteria
1. The watermark runs on a `tile-render-` thread. Access checks and storage fetch don't (thread-name capture in a test).
2. With the pool and queue saturated (a test with pool 1, queue 1, and a blocking renderer stub), the next request gets 503 + `Retry-After` **immediately**, without waiting for the timeout.
3. A render exceeding the timeout gets 503 and the pool thread is not leaked (the stub is interruptible; assert the active count returns to 0).
4. GraphQL and heartbeat latency stay flat while the render pool is saturated. This is the bulkhead proof: integration test measuring a heartbeat during saturation, below 200 ms.
5. Frontend retries a 503 with backoff and shows the busy hint after the retries run out.
6. The Phase 10 k6 smoke is re-run and recorded in `docs/perf/` with before/after.

## Out of scope
- Caching (Phase 13).
- Changing the renderer (Phase 14).
- Horizontal autoscaling.

## Learning note
Create `docs/learning-notes/phase-12-render-pool-backpressure.md`. Headline topics:
- the bulkhead pattern
- CPU-bound vs I/O-bound work
- virtual threads: what they are, what they don't help with, and pinning
- bounded queues and why unbounded queues are latency bombs
- 429 vs 503
- timeouts and cancellation
- Little's Law, applied to pool sizing
