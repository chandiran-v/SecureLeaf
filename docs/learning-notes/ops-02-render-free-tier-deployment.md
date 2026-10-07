# Ops 02 — Making SecureLeaf deployable on free managed services (Render + Vercel + Neon + Upstash + B2 + Brevo)

> **Status:** Done (code + config); not yet deployed
> **Built:** 2026-10-07
> **Requirement IDs covered:** none new. This is deployment readiness for the 5-user beta. Companion to [Perf 01](perf-01-purchase-time-watermarking.md), which made the app fast enough for a 0.1-CPU host.
> **Guide:** [`docs/deployment-render.md`](../deployment-render.md)

---

## 1. What we built, in plain English

The app was built to run as **one server** (Oracle, Phase 09D), where everything sits behind one web address: the website, the API, Postgres, Redis and file storage all live on the same machine. For a free, no-credit-card beta we split it across six services instead: the website on **Vercel**, the backend on **Render**, the database on **Neon**, Redis on **Upstash**, files on **Backblaze B2**, and email via **Brevo**.

Moving from "one box" to "six services on different domains" changes assumptions the code had quietly relied on. We found and fixed **three bugs that would only have appeared in production**, and added the configuration Render needs:

- a `render` Spring profile and a `render.yaml` blueprint carrying the **measured** memory settings;
- email over **HTTPS** (Render's free tier blocks SMTP);
- TLS for Redis everywhere, including a connection that ignored it;
- the reader's page URLs resolved against the **backend's** domain, not the website's.

**Before:** deploying to Render + Vercel would have broken page reading, email and (silently) rate limiting, and could have published internal metrics.
**After:** one blueprint, one guide, guarded by tests.

---

## 2. Why it matters

Each of these bugs was invisible locally, where Vite's dev proxy and Docker Compose put everything on one origin, without TLS. That is the classic **"works on my machine"** gap. An interviewer asking "what changes when you move to managed services?" is asking exactly this: origins, TLS, ports and blocked egress.

---

## 3. New concepts introduced

### 3.1 Same-origin vs split-origin deployments

**What it is:** The browser's *origin* is scheme + host + port. With Caddy (Phase 09D), the website and API share one origin, so relative URLs like `/api/...` just work. With Vercel + Render, they are different origins.

**The analogy:** In one building, "room 204" is enough. Across two buildings you need the street address too.

**The bug it caused here:** the server signs tile URLs as **paths** (`/api/viewer/tiles/...`). The reader fetched them as-is. On Vercel, `fetch("/api/viewer/...")` goes to **Vercel**, whose SPA rewrite answers with `index.html`, so every page would fail to load. Locally, Vite's proxy hid this completely.

**In our code:** `frontend/src/lib/apiUrl.ts:12` resolves server-relative paths against the API's origin when `VITE_API_URL` is absolute, and leaves them alone otherwise (same-origin deployments are unchanged). Used at `useSecureTile.ts:151`.

**What else split-origin needs:** CORS on the backend (`CORS_ALLOWED_ORIGINS`), absolute API URLs in the frontend build (`VITE_*_URL`), and a Content-Security-Policy on Vercel that allows the backend, Google sign-in and the storage host (thumbnails are presigned links straight to B2).

### 3.2 The Adapter pattern: SMTP → HTTPS email without touching the mailers

**What it is:** Wrapping a different implementation behind the interface callers already use.

**Why we needed it here:** Render's free tier blocks SMTP ports (ADR 0001). Both mailers (`PasswordResetMailer`, `NotificationDispatcher`) depend on Spring's `JavaMailSender`. So `BrevoApiMailSender implements JavaMailSender` (`common/mail/BrevoApiMailSender.java:36`) and sends via Brevo's REST API. `BrevoMailConfig` registers it only when `mail.transport=brevo-api` (line 19). Spring Boot's own SMTP sender is `@ConditionalOnMissingBean`, so it steps aside automatically. **Zero changes to either mailer.**

**A privacy detail:** the mailers log `e.getMessage()` on failure, and Phase 9 forbids logging email addresses. Brevo's error text can echo the recipient, so the adapter reports only the HTTP status and Brevo's error *code* (`errorCode`, line 91). A unit test asserts the address never appears.

### 3.3 A second client that ignored TLS (fail-open turns a bug invisible)

**What it is:** The rate limiter (Phase 11) builds its **own** Redis client for Bucket4j, separate from Spring's. It copied host, port and password, but not `ssl`.

**Why it is nasty:** Upstash is TLS-only, so this client could never connect. The limiter is designed to **fail open** when Redis is down (Phase 11 D5), so the site would work perfectly while **rate limiting was silently off.** Nothing would alert. This is the trade-off of fail-open designs: they hide their own failures, so their dependencies must be configured exactly right.

**Fix:** `RateLimitConfig.java:41` mirrors `spring.data.redis.ssl.enabled` (and a username for Redis 6 ACLs).

### 3.4 One public port: the actuator exposure trap

**What it is:** In `prod`, actuator lives on a **private** management port (8081) that is never published, and `/actuator/prometheus` is `permitAll` *because* of that (Phase 10). Render exposes **one** port. To let Render health-check the app, actuator must move to the main port.

**The trap:** moving it naively also moves `/actuator/prometheus`, unauthenticated, to the public internet: request rates, error counts, user-id-free but detailed internals.

**Fix:** `application-render.yml:21` exposes **only `health`**. `RenderProfileTest` fails if anyone adds more. **Security controls that depend on network placement must be re-checked whenever the network changes.**

### 3.5 Choosing the health check: liveness, not readiness

Render restarts a service whose health check keeps failing. Readiness includes Postgres and Redis, so a short Neon or Upstash blip would trigger a restart, and **a restart is a 14-minute outage on 0.1 CPU** (Perf 01). Liveness only asks "is the JVM alive?". So `render.yaml:17` uses `/actuator/health/liveness`. The general rule is the same as Kubernetes: **restart on liveness, route on readiness.**

---

## 4. Best practices applied

| Practice | What we did | Why it matters | Where |
|---|---|---|---|
| Configuration as code | `render.yaml` blueprint with the measured JVM flags | The tested settings are what gets deployed | `render.yaml` |
| Secrets never in git | `sync: false` (asked in Render's UI); signing keys `generateValue` | A public repo can't leak them | `render.yaml` |
| Adapter over rewrite | `BrevoApiMailSender implements JavaMailSender` | No mailer changed; SMTP still works elsewhere | `common/mail/` |
| Test against the real contract without a network | `MockRestServiceServer` (as Phase 09B) | Request shape and error handling verified offline | `BrevoApiMailSenderTest` |
| Least exposure | Only `health` on the public port, guarded by a test | Metrics stay private | `application-render.yml`, `RenderProfileTest` |
| Restart on liveness, not readiness | `healthCheckPath: /actuator/health/liveness` | A DB blip doesn't cause a 14-min outage | `render.yaml:17` |
| TLS to every hosted dependency | `sslmode=require`, Redis `ssl.enabled` for both clients | Credentials and data cross the internet | profile + `RateLimitConfig` |
| Deliberate deploys | `autoDeploy: false` | Each deploy = ~14-min downtime | `render.yaml:18` |
| Docs that don't contradict | Old Plan B appendix points to the new guide | One source of truth | `docs/deployment.md` |

---

## 5. What does what — file map

| File | Responsibility |
|---|---|
| `render.yaml` | Render service: Docker build, free plan, Singapore, health check, env vars |
| `backend/src/main/resources/application-render.yml` | `$PORT`, actuator on the shared port (health only), Neon TLS, Redis TLS, async/render timeouts, Brevo |
| `backend/.../common/mail/BrevoApiMailSender.java`, `BrevoMailConfig.java` | Email over HTTPS |
| `backend/.../ratelimit/RateLimitConfig.java` | Rate-limiter Redis client now honours TLS |
| `backend/.../common/config/MinioConfig.java`, `MinioProperties.java` | Optional S3 region (Backblaze B2) |
| `frontend/src/lib/apiUrl.ts`, `hooks/viewer/useSecureTile.ts` | Split-origin tile URLs |
| `frontend/vercel.json` | CSP for Render, Google sign-in, B2 thumbnails, Sentry |
| `docs/deployment-render.md` | Step-by-step guide |

---

## 6. Design decisions and trade-offs

### Decision: six free services instead of one server
- **Alternatives considered:** Oracle Always Free (the plan, but no Ampere capacity in Mumbai for days); Google Cloud e2-micro (1 GB, needs a billing account, 1 GB/month egress); AWS (free plan closes after 6 months); home PC + tunnel (rejected by the owner).
- **Why:** the only combination that is ₹0 with no card anywhere, so a limit can stop a service but never bill.
- **What we gave up:** speed (0.1 CPU), a ~14-minute startup on every deploy, more moving parts, and Vercel Hobby's non-commercial clause.
- **When we would revisit:** when Oracle capacity frees up (the retry script exists), or before live payments (Phase 18).

### Decision: Brevo via HTTPS adapter, not a different email provider SDK
- **Why:** Brevo was already the chosen provider (09D D7); the adapter keeps one email path in the code and needs no SDK.
- **Gave up:** HTML/MIME email on this path (the app sends plain text only, and the adapter throws on MIME rather than silently dropping mail).

---

## 7. Interview questions

### Beginner
**Q: Why did page images break when the frontend moved to Vercel?**
A: The server returns tile URLs as paths like `/api/viewer/...`. A relative path is resolved against the page's own origin, which was now Vercel, not the backend. Locally the Vite proxy hid it. I resolve those paths against the API's origin when it's on another domain.

**Q: How do you send email if SMTP is blocked?**
A: Through the provider's HTTPS API. I wrote an adapter that implements Spring's `JavaMailSender`, so the existing mailers didn't change at all. It's switched on by a property only on Render.

### Intermediate
**Q: Why was the rate limiter's Redis connection a problem when everything else worked?**
A: It's a separate Lettuce client for Bucket4j, built by hand, and it didn't copy the TLS flag. Upstash requires TLS, so it could never connect. Because the limiter fails open by design, the site would work while rate limiting was silently disabled. Fail-open hides its own failures.

**Q: Why use the liveness endpoint for Render's health check?**
A: Render restarts on failing health checks. Readiness includes the database and Redis, so a brief Neon blip would restart the app, which takes 14 minutes on this CPU. Liveness only says whether the JVM is alive. Restart on liveness, route traffic on readiness.

### Advanced
**Q: What security control silently depended on the network layout?**
A: `/actuator/prometheus` is open without auth in prod because it's on a private, unpublished management port. Render has one public port, so naively moving actuator there would publish all metrics. I expose only `health` in the Render profile and added a test that fails if that list grows.

**Q: How do you keep secrets out of a blueprint that lives in a public repo?**
A: `sync: false` entries make Render prompt for values in its dashboard, and `generateValue` creates the signing keys on Render's side. The repo holds only names and comments.

### "Tell me about a bug you fixed"
**Q: Tell me about a bug you caught before it reached production.**
A: Preparing a split deployment, I reviewed every place the app assumed one origin. The reader fetched signed tile URLs as relative paths. On Vercel that would hit the static site and return HTML instead of an image, so every book would fail to open. The dev proxy had always hidden it. I added a small resolver that uses the API's origin when it's absolute, tested it for both deployment shapes, and wrote it into the guide.

---

## 8. Gotchas and bugs we hit

| Symptom (would-be) | Root cause | Fix | Lesson |
|---|---|---|---|
| Every page in the reader fails on Vercel | Signed tile URL is server-relative; resolved against Vercel | `resolveApiUrl` | Dev proxies hide origin bugs; test the split-origin shape |
| Rate limiting silently off on Upstash | Hand-built Lettuce client ignored `ssl.enabled`; limiter fails open | `.withSsl(...)` from `RedisProperties` | Every client of a dependency must get the same TLS/auth settings |
| Metrics public on Render | Actuator moved to the only public port with `prometheus` exposed | Expose only `health`; `RenderProfileTest` | Re-check placement-based security when the network changes |
| Password-reset emails never sent | SMTP blocked on Render free | `BrevoApiMailSender` adapter | Check the host's egress rules |
| Thumbnails / Google sign-in blocked by CSP on Vercel | `vercel.json` CSP lagged behind Caddy's | Aligned it; added B2 and the Render host | Keep security headers in sync across deployment targets |
| *(diff hygiene)* rewriting `vercel.json` reformatted the whole file | `json.dumps` re-indented everything | Restored the file and replaced only the CSP string | Keep diffs minimal: reviewers read diffs, not files |

---

## 9. New vocabulary

| Term | One-line meaning |
|---|---|
| **Origin** | Scheme + host + port; the browser's unit of "same site" |
| **Split-origin deployment** | Frontend and API on different domains; needs absolute URLs, CORS and CSP entries |
| **Adapter pattern** | Implement the interface callers expect, on top of a different mechanism |
| **Liveness vs readiness** | "Restart me if this fails" vs "stop sending me traffic while this fails" |
| **Blueprint (Render)** | A `render.yaml` that declares services and env vars as code |

---

## 10. If I had to defend this in a code review

- **Strongest point:** every free-tier assumption that could fail silently (TLS on a second client, public metrics, relative URLs) is either fixed with a test or written into the guide with a check command.
- **Also strong:** no business code changed. Mail is an adapter, the profile is additive, and Oracle/Compose deployments behave exactly as before.
- **Honest limitation:** it has not been deployed yet. Render's real CPU behaviour, its deploy timeout versus our ~14-minute start, and B2's latency from Singapore are only measurable there.
- **Weakest point, fix first:** startup time. A 14-minute start means every deploy or restart is an outage, and Render may time the deploy out. AppCDS / Spring AOT, measured the same way, is next.
