# Phase 11 — Per-buyer rate limiting (Bucket4j + Redis)

> Spec for an unattended run. Decisions are numbered (D1…) and referenced from code comments.
> Requirements: **MVP2-04**.
> Depends on: Phase 10 (metrics). Target branch: `feature/secure-leaf-mvp2`.

## Context

A scraper holding a valid entitlement can loop through `viewerPageUrl` and the tile endpoint and rip a whole book, one watermarked page at a time. Every one of those requests costs a watermark render. The public preview endpoint (`PreviewController`) is also an unauthenticated CPU-burning endpoint; its own Javadoc names it a DoS surface.

A real reader turns a page every few seconds, so a hard per-buyer limit costs honest users nothing and stops bulk ripping.

## Decisions

- **D1 — Algorithm: token bucket.** Use Bucket4j with the `bucket4j-redis` Lettuce-based proxy manager, so limits are shared across instances. The learning note compares token bucket with fixed window (boundary bursts), sliding log (memory) and leaky bucket.
- **D2 — Limits.** All of these are configuration under `ratelimit.*`, not hard-coded:

  | Key | Bucket | Refill |
  |---|---|---|
  | `tile:{userId}` | capacity 5 | 2 tokens/s (MVP2-04: 2 req/s sustained, small burst for prefetch) |
  | `pageurl:{userId}` | capacity 10 | 4/s |
  | `preview:{clientIp}` | capacity 20 | 1/s |
  | `login:{ip}` | keep Phase 9's counter | — |
- **D3 — Where it's enforced.** In a `OncePerRequestFilter` placed **after** authentication (so the user id is known) for `/api/viewer/**`, and before authentication for `/api/products/*/preview/*`. The `viewerPageUrl` limit is enforced in the resolver.
  - A rejected request returns **429** with `Retry-After` (seconds, rounded up) and `X-RateLimit-Remaining`. GraphQL gets error code `RATE_LIMITED`, with `retryAfterSeconds` in the extensions.
  - The limit check comes **before** any storage fetch or rendering.
- **D4 — Client IP.** Take it from `X-Forwarded-For` **only** when the request comes from a trusted proxy CIDR (`ratelimit.trusted-proxies`). Otherwise use `remoteAddr`. The learning note explains XFF spoofing.
- **D5 — Redis failure.** Fail open: allow the request, log a WARN, and increment `secureleaf.ratelimit.redis_errors`. Rate limiting is protection, not correctness. This is a documented trade-off, consistent with Phase 8's reasoning.
- **D6 — Abuse signal.** Count rejections in `secureleaf.ratelimit.rejected{bucket}`. If one user is rejected more than 100 times in 10 minutes, log a WARN "possible scraper userId=…" (id only), and add a query for the admin audit view: `suspectedScrapers(windowMinutes)`, computed from `viewer_access_logs`.
- **D7 — Frontend.** The viewer handles a 429 by waiting `Retry-After` and then retrying once, with a subtle "Slow down…" hint. Prefetch skips when it would exceed the budget. A normal reading pace must never see a 429.

## Acceptance criteria
1. 6 immediate tile requests from one user: 5 succeed and the 6th gets 429 with `Retry-After`. After waiting, requests succeed again (use Bucket4j's time meter or a `Clock` so the test doesn't `sleep`).
2. Limits are per user: user A being throttled doesn't affect user B.
3. Limits are shared across two app contexts pointing at the same Redis (integration test with two contexts, or a direct proxy-manager test).
4. The preview limit is keyed by IP. A spoofed `X-Forwarded-For` from an untrusted source is ignored.
5. A rejected request never touches storage or the renderer (verify with a spy).
6. With Redis down, the request is allowed and the error metric increments.
7. Frontend: a 429 is retried after `Retry-After`, and the hint is shown.
8. A k6 scenario, `loadtest/scraper.js`: one user hammering tiles. The documented result is mostly 429s at about 2 req/s effective.

## Out of scope
- A WAF or CDN-level limit.
- CAPTCHA.
- Automatic suspension (admins decide).

## Learning note
Create `docs/learning-notes/phase-11-rate-limiting.md`. Headline topics:
- token bucket and the other algorithms
- distributed rate limiting and atomicity (why Bucket4j uses CAS/Lua in Redis)
- 429 + `Retry-After` semantics
- XFF trust
- fail-open vs fail-closed, revisited
- rate limiting as a DRM control (limits the *speed* of ripping, and pairs with the watermark for traceability)
