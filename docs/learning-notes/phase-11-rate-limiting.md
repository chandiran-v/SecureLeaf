# Phase 11 — Per-buyer rate limiting (Bucket4j + Redis)

> **Status:** Done
> **Built:** 2026-09-29
> **Requirement IDs covered:** MVP2-04 (buyer-level rate limiting). Spec: `docs/phases/phase-11-rate-limiting.md`.
> **Commits:** see the `auto/issue-11` branch (`Phase 11: …`)

---

## 1. What we built, in plain English

A buyer who owns a book can read it page by page in the secure viewer. Every page is a watermarked
picture that the server draws on demand — real CPU work. Before this phase nothing stopped that buyer
(or a script logged in as them) from asking for page 1, 2, 3 … 300 as fast as the network allows and
saving every picture: a whole book "ripped" in a minute, each page carrying the buyer's watermark but
still copied. The public free-preview endpoint had a different problem: anyone on the internet, no
login, could make the server draw watermarked pages all day.

Now every buyer gets a small "bucket of tokens" for tile requests. Each request takes a token; tokens
drip back at 2 per second; the bucket holds at most 5. A person turning a page every few seconds never
notices. A script asking 50 times a second gets its first five pages and then a `429 Too Many Requests`
answer that says how many seconds to wait. The same idea, keyed by IP address instead of user, protects
the free preview. The viewer in the browser understands the 429: it waits the requested time, retries
once and shows a small "Slow down…" hint.

**Before this phase:** no limit on tile, page-URL or preview requests; the preview Javadoc literally called itself a DoS surface.
**After this phase:** per-buyer and per-IP token buckets shared through Redis; 429 + `Retry-After`; metrics; a "possible scraper" log line and an admin query.

---

## 2. Why it matters

DRM here is not one lock, it is layers: the canvas viewer (no download button), single-use signed URLs,
a watermark that names the buyer, an audit log. None of those limit the **speed** of copying. A
patient scraper with a valid entitlement gets every page through the front door. Rate limiting is the
layer that turns "rip the book in a minute" into "rip the book in an hour, while your account shows a
suspicious pattern and every page carries your name" — which is what makes the watermark a *traceable*
deterrent rather than a decoration. It also protects the server: every tile costs a watermark render.

Skipping it means the cheapest attack on the product stays cheap, and the one unauthenticated
CPU-burning endpoint stays open to a single-laptop denial of service.

---

## 3. New concepts introduced

### 3.1 Token bucket

**What it is:** a counter of "permission slips" that refills at a steady rate up to a maximum. Each request spends one; no slip, no service.

**The analogy:** a coffee-shop loyalty jar that gets 2 beans an hour up to 5 beans. You can spend 5 at once (a burst) but over a day you can only average 2 an hour.

**Why we needed it here:** honest reading is bursty (open a page, the viewer prefetches the next) but slow on average. A token bucket says yes to the burst and no to the sustained flood.

**How it works:**
1. Bucket starts full (5 tokens).
2. A request arrives: refill first (`elapsed × rate`, capped at capacity), then try to take 1.
3. Took one → allow. Bucket empty → reject, and the time until the next token is `1 / rate`.

Nothing runs in the background: refill is *computed lazily* from the timestamp of the last request. That is why a million idle buckets cost nothing.

**In our code:** `backend/src/main/java/com/secureleaf/ratelimit/RateLimiter.java:78`
```java
probe = proxyManager()
        .builder()
        .build(redisKey(bucket, key), () -> configurations.get(bucket))
        .tryConsumeAndReturnRemaining(1);
```
Numbers live in `application.yml` under `ratelimit.*` (D2), never in Java.

**What breaks without it:** with the *other* algorithms, see §6 — a fixed window lets a scraper do 2× the limit at a window boundary.

### 3.2 Distributed rate limiting and atomicity

**What it is:** the same bucket must be shared by every app instance, otherwise a scraper gets N× the budget by hitting N servers.

**The analogy:** one jar on the counter, not one jar per cashier.

**How it works:** the bucket's state (tokens, last-refill time) is one small value in Redis. Two requests may race for the last token, so read-modify-write must be atomic. Bucket4j's Lettuce proxy manager does a **compare-and-swap** through a Lua script: "replace the value only if it is still what I read; otherwise re-read and retry". No lock is held, and exactly one racer wins the last token.

**In our code:** `RateLimitConfig.java:58` builds the `LettuceBasedProxyManager`; `RateLimiterTest.concurrentRequestsCannotOverspendTheBucket` fires 40 requests on 16 threads at a 5-token bucket and asserts exactly 5 succeed. `bucketsAreSharedAcrossTwoAppInstancesUsingTheSameRedis` uses two independent Redis connections as "two instances".

**What breaks without it:** check-then-set in application code (`GET`, compare, `SET`) lets two requests both see "1 token left" and both proceed — the limit leaks under exactly the concurrent load it is there for.

### 3.3 HTTP 429 and `Retry-After`

**What it is:** `429 Too Many Requests` is the standard "you are going too fast" status; `Retry-After: N` tells a well-behaved client to wait N seconds.

**Why it matters:** without `Retry-After` clients guess, and guessing clients retry immediately — making it worse. We round **up** (`RateLimiter.java`, `secondsRoundedUp`) and never return 0, so a client that waits exactly the stated time is never rejected twice. GraphQL always answers HTTP 200, so there the same information travels as error code `RATE_LIMITED` with `extensions.retryAfterSeconds`. We also send `X-RateLimit-Remaining` so clients can slow down *before* being rejected.

### 3.4 Trusting `X-Forwarded-For`

**What it is:** when a proxy (Caddy) sits in front of the app, the app's TCP peer is always the proxy. The proxy passes the visitor's address in the `X-Forwarded-For` header.

**The attack:** a header is text anyone can send. If we believed it always, an attacker sends `X-Forwarded-For: <random>` on every request and gets a brand-new preview bucket each time.

**Our rule (D4):** believe the header **only** when the TCP peer is inside `ratelimit.trusted-proxies`; then walk the list right-to-left and take the first address that is not itself a trusted proxy (each proxy appends on the right, so everything on the left was typed by the client).

**In our code:** `ClientIpResolver.java:42`; tested by `ClientIpResolverTest` and, end to end, `RateLimitIT.spoofedForwardedForFromAnUntrustedPeerDoesNotBuyAFreshBucket`.

### 3.5 Fail open vs fail closed (revisited)

**What it is:** what to do when the limiter's own dependency (Redis) is down. **Fail closed** = deny everything; **fail open** = allow everything.

**Our choice (D5):** fail **open** — log a WARN, increment `secureleaf.ratelimit.redis_errors`, allow. Rate limiting is *protection*, not *correctness*: if Redis hiccups, paying customers must still be able to read the book they bought. Contrast with Phase 5's entitlement check, which fails **closed** because letting through a non-owner is a correctness/security breach. Same question, opposite answers, because the cost of a wrong "yes" differs.

**Detail that matters:** after a failure we skip Redis for 5 seconds (`BACKOFF_NANOS`, `RateLimiter.java:44`) so a dead Redis costs one 500 ms timeout, not one per request.

### 3.6 Rate limiting as a DRM control

The limit does not make copying impossible; it caps the **speed**. Combined with the per-buyer watermark and `viewer_access_logs`, an attacker either takes hours (and shows up in the logs) or is traceable from any leaked page. See §6 "weakest point" for what it does not stop.

### 3.7 Abuse signal (D6)

Two independent signals because each has a blind spot. A Redis counter of *rejections* per user (10-minute window) logs `possible scraper userId=…` once when it passes 100 — it catches someone being throttled right now. The admin query `suspectedScrapers(windowMinutes)` reads `viewer_access_logs` — durable, and catches a *slow* scraper who stays just under the limit (over 30 successful tiles/minute on average). We log the user id only, never an email; nothing suspends the account (admins decide).

---

## 4. Best practices applied

| Practice | What we did | Why it matters | Where |
|---|---|---|---|
| Config, not constants | every capacity/refill/CIDR/threshold under `ratelimit.*` | tighten under attack without a redeploy | `application.yml`, `RateLimitProperties.java` |
| Cheap rejection | filter runs before controller; a rejected request never reaches storage or the renderer | rejecting must cost less than serving | `RateLimitFilter.java:70`; `ViewerIT.rateLimit_sixthImmediateTile…` spies storage reads |
| Injectable time | `TimeMeter` passed into the proxy manager | tests "wait" by winding a fake clock, no `sleep` | `RateLimitConfig.proxyManager`, `RateLimiterTest.ManualClock` |
| Bounded storage | Redis key expires once the bucket would be full again | no key leak from one-off IPs | `RateLimitConfig.java:58` |
| Lazy connection | Bucket4j connects on first use, not at startup | a down Redis must not stop the app booting | `RateLimiter.proxyManager()` |
| Low-cardinality metrics | tag is the bucket *kind*, never a user id or IP | unbounded tags explode Prometheus | `RateLimiter.REJECTED` |
| Privacy in logs | scraper WARN logs only the numeric user id | audit signal without PII | `ScraperSignalService.java:58` |

---

## 5. What does what — file map

| File | Responsibility |
|---|---|
| `ratelimit/RateLimitProperties.java` | typed `ratelimit.*` config (D2) |
| `ratelimit/RateLimiter.java` | token-bucket check, fail-open, metrics |
| `ratelimit/RateLimitConfig.java` | Lettuce client + Bucket4j proxy manager wiring |
| `ratelimit/RateLimitFilter.java` | HTTP enforcement: tile (by user) and preview (by IP), writes the 429 |
| `ratelimit/ClientIpResolver.java` | D4 — who is really calling |
| `ratelimit/ForwardedForCaptureFilter.java` | keeps the raw `X-Forwarded-For` before Spring's forwarded-header filter hides it |
| `ratelimit/RateLimitedException.java` | resolver-side rejection carrying `retryAfterSeconds` |
| `ratelimit/ScraperSignalService.java` | rejection counter, WARN, `suspectedScrapers` |
| `viewer/resolver/ViewerResolver.java:55` | `viewerPageUrl` limit |
| `admin/resolver/SuspectedScraperResolver.java` | admin GraphQL query |
| `frontend/src/lib/rateLimit.ts` | retry-once-after-`Retry-After`, prefetch budget |
| `frontend/src/hooks/viewer/useSecureTile.ts` | uses them; exposes `slowDown` for the hint |
| `loadtest/scraper.js` | k6 scraper scenario |

**Request trace — one tile:**
1. Browser `GET /api/viewer/tiles/{session}/{page}?exp&sig` with a JWT →
2. `JwtAuthenticationFilter` puts the user in the security context →
3. `RateLimitFilter` takes a token from `tile:{userId}` in Redis (`SecurityConfig.java:140`) →
4. token available → controller → signature/session/entitlement checks → storage → watermark → 200;
   no token → **429 + `Retry-After`** written right here, controller never runs.

---

## 6. Design decisions and trade-offs

### Decision: token bucket (Bucket4j) over other algorithms
- **Alternatives considered:** *fixed window counter* (`INCR` per second): trivial, but a client can send the full quota at :59.9 and again at :00.1 — 2× the limit in a blink. *Sliding-window log* (store every timestamp): exact, but memory grows with the limit and every request is a sorted-set operation. *Leaky bucket* (a queue drained at a constant rate): smooths traffic perfectly, but we do not want to *queue* page requests, we want to answer immediately, and it allows no burst.
- **Why we chose this:** O(1) state, allows the small prefetch burst, precise `Retry-After` for free.
- **What we gave up:** the limit is "average rate + burst", not a hard "never more than N in any window".
- **When we would revisit:** if we needed exact billing-grade quotas (sliding log) or traffic shaping (leaky bucket).

### Decision: Bucket4j library over a hand-written Redis Lua script
- **Why:** the CAS retry loop, expiry and clock handling are exactly the kind of code that is wrong the first three times. **Gave up:** a dependency (`bucket4j_jdk17-lettuce`) and a second Lettuce client alongside Spring Data Redis, because Bucket4j needs raw `byte[]` codecs.

### Decision: fail open on Redis failure (D5)
- **Alternative:** fail closed. **Why not:** turns a cache outage into a product outage for paying users. **Cost:** while Redis is down, no limiting — an attacker who can *cause* a Redis outage gets a free window. **Revisit:** if Redis becomes attackable from outside (it is not published).

### Decision: one filter after JWT for both endpoints
- The spec said the preview limit should run "before authentication". One filter placed after `JwtAuthenticationFilter` serves both: the preview path is `permitAll` and IP-keyed, so nothing about it depends on authentication. A second filter would only add a class. Deliberate deviation, recorded here.

### Decision: keep Phase 9's login counter
- The login throttle keys on email+IP and counts *failures*; a token bucket would answer a different question. Left alone, per D2.

---

## 7. Interview questions

### Beginner
**Q: What is rate limiting and why do we need it here?**
A: Capping how fast one client can call an endpoint. Here each tile costs a watermark render, and a scraper with a valid purchase could copy a whole book by asking for pages in a loop. A person turns a page every few seconds, so a small limit costs honest users nothing.

**Q: What status code do you return, and what header goes with it?**
A: 429 Too Many Requests with `Retry-After` in seconds so the client knows when to come back.

### Intermediate
**Q: Explain a token bucket.**
A: A bucket holds up to N tokens and gains R per second. Each request takes one. Empty means reject. So you can burst up to N, but on average never exceed R per second. Refill is computed from the time since the last request, so there is no timer thread.

**Q: Why not a fixed-window counter?**
A: Boundary bursts. With 10 per second, someone sends 10 at 0.99 s and 10 at 1.01 s — 20 requests in 20 ms and the counter never complained. The token bucket has no boundary.

**Q: Why is the state in Redis rather than in memory?**
A: With more than one instance, in-memory buckets multiply the allowance by the instance count and reset on every deploy. Redis makes it one shared bucket.

**Q: How do you know your key for the preview limit is trustworthy?**
A: We only trust `X-Forwarded-For` when the TCP peer is one of our proxies; otherwise we use the socket address. Anyone can type a header.

### Advanced / follow-up probes
**Q: Two requests race for the last token on two servers. What happens?**
A: Bucket4j's Redis proxy manager reads the bucket, computes the new state, and writes it back with a compare-and-swap in a Lua script. The loser sees a changed value, re-reads and retries — by then the bucket is empty, so it is rejected. Our test throws 40 concurrent requests at a 5-token bucket and asserts exactly 5 pass.

**Q: Redis is down. Allow or deny?**
A: Allow — fail open — and count `redis_errors`. The limiter is protection, not correctness, and denying would make a cache outage look like the product being down. For the *entitlement* check I would fail closed, because there a wrong "yes" is a breach. And we back off for 5 seconds so a dead Redis costs one timeout, not one per request.

**Q: Clocks differ between servers. Does that break the bucket?**
A: With the CAS proxy manager the refill uses the *client's* clock, so a server whose clock is fast refills faster. NTP-level skew (milliseconds) is irrelevant at a per-second rate; large skew would matter and we would then use Redis `TIME` as the source.

**Q: A scraper rotates accounts. Does this help?**
A: Not by itself — the limit is per buyer, and each account needs a paid entitlement. What helps is the signal: `suspectedScrapers` and the per-account watermark mean each rotated account is a paid, traceable identity. Real defence is layered.

### "Tell me about a bug you fixed"
**Q: Something surprising you hit in this phase?**
A: In production Spring's `ForwardedHeaderFilter` (`forward-headers-strategy: framework`) *removes* `X-Forwarded-For` from the request. My limiter's IP logic was correct and would have seen no header at all — every visitor behind Caddy would have shared Caddy's IP and one preview bucket. Reading Spring's source found it; the fix is a tiny first-in-chain filter that copies the header into a request attribute before it is hidden. The lesson: test the *deployed* filter order, not just the unit.

---

## 8. Gotchas and bugs we hit

| Symptom | Root cause | Fix | Lesson |
|---|---|---|---|
| (found by reading, prod-only) all visitors would share one preview bucket | `ForwardedHeaderFilter` strips `X-Forwarded-For` before our filter runs | `ForwardedForCaptureFilter` at highest precedence copies it to a request attribute | Proxy-header handling has framework-order pitfalls that only show behind a real proxy |
| App would not start if Redis was down | Bucket4j's `builderFor(RedisClient)` connects immediately | proxy manager built lazily on first use | An optional protective dependency must never gate startup |
| Dead Redis would add 500 ms to every request | each request re-tried the connection | 5 s back-off after a failure; `REJECT_COMMANDS` when disconnected | Fail-open must also be fail-*fast* |
| Retry-After of 0 invites an instant second rejection | truncating nanos to seconds | ceiling, minimum 1 | Round waits up |
| Flaky-test risk: page-URL bucket refills 4/s during a slow HTTP loop | test drove the limit through GraphQL | drain the bucket directly via `RateLimiter`, then assert one GraphQL call | Drive limit tests with injected time or direct calls, never wall-clock loops |

---

## 9. New vocabulary

| Term | One-line meaning |
|---|---|
| Token bucket | Refilling counter of permits; allows bursts, caps the average rate |
| Fixed window / sliding log / leaky bucket | The rejected alternatives (boundary bursts / memory-hungry / no burst, queues) |
| 429 / `Retry-After` | "Too many requests" status / seconds to wait before retrying |
| Compare-and-swap (CAS) | Write only if the value is unchanged since you read it; else retry |
| Fail open / fail closed | Allow / deny when the safety check itself is broken |
| `X-Forwarded-For` | Header a proxy uses to pass on the visitor's IP; spoofable unless the sender is trusted |
| CIDR | `10.0.0.0/8` notation for an IP range |
| Scraper | A client copying content in bulk with automation |

---

## 10. If I had to defend this in a code review

- **Strongest:** one small class owns the algorithm, all numbers are config, the check happens before any expensive work, and every acceptance criterion has a test that does not sleep.
- **Strong:** the fail-open choice is written down with its cost and contrasted with the fail-closed entitlement check.
- **Weakest — first thing I would fix:** the limit is per *account*, and the tile/page-URL limit is keyed on user id only. An attacker who buys (or steals) several entitlements multiplies their speed, and a slow scraper under 2 tiles/s is never rejected — only the `suspectedScrapers` query sees them, and a human has to look. Next step: alert on that query, and add a per-*product* daily page budget per buyer.
- **Also honest:** the k6 scraper scenario (`loadtest/scraper.js`) is written and syntax-checked, but its numbers have not been recorded from a real run yet, and I did not verify the prod `X-Forwarded-For` path behind a real Caddy (only by unit test and by reading Spring's source).
