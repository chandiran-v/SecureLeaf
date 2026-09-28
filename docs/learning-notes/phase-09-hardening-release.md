# Phase 9 — Hardening & release readiness

> **Status:** Done
> **Built:** 2026-09-28
> **Requirement IDs covered:** Non-functional requirements (observability, security, reliability) — from `docs/requirements.md`
> **Commits:** see `git log` on `auto/issue-9`

---

## 1. What we built, in plain English

**Before this phase:** SecureLeaf's features all worked, but the app couldn't answer basic
operational questions. If a request failed at 2am, there was no way to find every log line it
touched. Logs were free-text, unstructured, and — in a couple of places — quietly included a
buyer's email address. The API sent no security headers beyond Spring's own defaults: no
Content-Security-Policy, no `Permissions-Policy`, and `Referrer-Policy` wasn't set at all. Someone
could script a GraphQL query nested 50 levels deep, or alias one expensive field 10,000 times, and
the server would try to answer it. Nothing stopped a script from guessing passwords against one
account thousands of times a minute. Every production secret had a documented, working default
value sitting in the repo — including the two that would let an attacker forge a "paid" checkout
or mint their own login tokens — and nothing would stop the app from happily starting with those
defaults still in place. There was no way for a load balancer to ask "is this instance actually
ready to serve traffic, or just alive." And nobody had ever proven, end to end, that a real browser
hitting the real app could complete the entire golden path — register, upload, buy, read — because
every test in the suite exercises one layer at a time.

**After this phase:** every request carries a correlation id from the moment it enters the system
to the moment it leaves, visible in every log line it touches (including ones written from a
background thread) and quoted back to the user if something goes wrong. Production logs are
structured JSON, and a test fails the build if a future change logs a password, token, or secret
by name. The API answers with a real set of security headers. GraphQL queries are capped at depth
10 and complexity 200, and introspection — the schema's own "describe yourself" feature — is
switched off in production. Five wrong passwords for the same account from the same IP inside 15
minutes gets the sixth attempt rejected outright, no password check even attempted. The app now
refuses to start in production if any of five specific secrets is still at its published, public
default. `/actuator/health/readiness` reports whether the database and Redis are actually reachable
right now, separately from whether the JVM process itself is alive. And a Playwright test drives an
actual Chromium browser through the complete MVP1 journey — against a real, running backend,
database, and cache, with no mocked layer — proving the whole system, not just its parts, works.

---

## 2. Why it matters

Every feature phase before this one answered "can a user do X." This phase answers a different
question: "when this is running for real, on the internet, and something goes wrong — will anyone
be able to tell what happened, and will the thing that went wrong be contained?" That question is
only answerable once every other feature exists, which is why it's the last MVP1 phase: it audits
and hardens the whole system, not one slice of it.

If we skipped it: a leaked default secret hands an attacker free purchases or forged logins with no
warning. A single scripted GraphQL query could tie up the server. Nobody could trace a bug report
back to the request that caused it. A production incident would be debugged by grep-ing free-text
logs and hoping. And "the app has never actually been proven to work end-to-end from a browser" is
not a sentence you want to be true the day it goes live.

---

## 3. New concepts introduced

### 3.1 Correlation IDs and MDC propagation across threads

**What it is:** A correlation id is one value, generated (or accepted from the caller) at the very
start of a request, that gets attached to every log line written while that request is being
handled — however many methods, services, or threads it passes through.

**The analogy:** It's a claim-check number handed out at a dry cleaner's with three separate
back-room stations. Each station has no idea who dropped the garment off or which other stations
touched it — except that every ticket, at every station, carries the same number. Trace one number
and you can reconstruct the garment's whole path, no matter how many hands it passed through.

**Why we needed it here:** A single SecureLeaf HTTP request can touch a GraphQL resolver, a
service, a repository, and — for anything that fires an `@Async` side effect (a purchase
confirmation email, a content-processing job) — an entirely different thread from a thread pool.
Without a correlation id, "grep the logs for what happened to this one failed request" is
impossible once more than one request is in flight, because every log line looks the same:
`"Order 42 created"` tells you nothing about *which* HTTP call created it if two calls happen in
the same second.

**How it works:**
1. `CorrelationIdFilter`, the very first filter in the chain, reads `X-Correlation-Id` from the
   incoming request, or mints a fresh UUID if the caller didn't send one.
2. It puts that value in SLF4J's MDC (**Mapped Diagnostic Context** — a per-thread key/value map the
   logging framework reads automatically) under the key `correlationId`, and echoes it back on the
   response header.
3. Every `log.info(...)` call anywhere on that thread, for the rest of the request, picks the value
   up for free — nothing has to pass it explicitly.
4. In `finally`, the filter removes the key — otherwise, because Tomcat reuses request-handling
   threads, the *next*, unrelated request on that same thread would inherit a stale id until
   something overwrote it.

**In our code:** `backend/src/main/java/com/secureleaf/common/web/CorrelationIdFilter.java:38`
```java
String correlationId = firstNonBlank(request.getHeader(HEADER), UUID.randomUUID().toString());
MDC.put(MDC_KEY, correlationId);
response.setHeader(HEADER, correlationId);
try {
    filterChain.doFilter(request, response);
} finally {
    MDC.remove(MDC_KEY);
}
```

**What breaks without it:** MDC is backed by a `ThreadLocal`. `@Async` methods run on a
*different*, pre-existing pool thread that has never heard of this request — so without extra
work, every log line from inside the content-processing pipeline or a purchase-confirmation email
would have no correlation id at all, breaking the "trace one request end-to-end" promise the moment
work crosses a thread boundary. `MdcTaskDecorator` (`backend/src/main/java/com/secureleaf/common/config/MdcTaskDecorator.java`)
closes that gap: it copies the calling thread's MDC onto the pool thread before the task runs, and
restores the pool thread's previous MDC (or clears it) afterward, so the *next*, unrelated task
picked up by that same pool thread doesn't inherit this request's id either.

---

### 3.2 Structured logging, and what never to log

**What it is:** Instead of a human-readable line of free text
(`2026-09-28 10:00:00 INFO Order 42 created`), a structured log line is one JSON object per line
(`{"timestamp": "...", "level": "INFO", "message": "Order 42 created", "correlationId": "..."}`).

**The analogy:** Free text is a paragraph describing a spreadsheet; structured logging *is* the
spreadsheet. A human reads a paragraph faster. A machine — a log aggregator that needs to filter by
level, search by correlation id, or count errors per minute — needs columns, not prose.

**Why we needed it here:** Dev and local debugging are done by a human reading a terminal, where a
short aligned line is faster to scan. Production logs are read by whatever log-aggregation tool the
owner points at Render's log stream, and that tool wants to filter/index fields, not regex free
text. Spring Boot 3.4+ ships a one-line property for this (`logging.structured.format.console:
ecs`); this project pins Boot 3.3.2 (see `pom.xml`), which predates it, so `logstash-logback-encoder`
does the same job directly.

**How it works:** `logback-spring.xml` defines two `<springProfile>` blocks: `!prod` gets the plain
`ConsoleAppender` with Boot's normal readable pattern; `prod` gets a `LogstashEncoder`-backed
appender that serializes every log line — including whatever's in MDC — as one JSON object.
Because `correlationId` is already in MDC (see 3.1), every JSON log line in production
automatically carries it with zero extra code.

**In our code:** `backend/src/main/resources/logback-spring.xml:38-53`

**What breaks without it:** the "never log a password, token, or secret" half of this section is
the more dangerous gap. Four places in this codebase (`AuthService.register`/`login`/`googleLogin`,
`AdminBootstrapService`) logged a user's *email address* alongside their id — not a secret exactly,
but personal data with no operational value the id alone doesn't already provide, and exactly the
kind of habit that eventually logs something worse. `LoggingHygieneTest`
(`backend/src/test/java/com/secureleaf/common/LoggingHygieneTest.java`) is a static scan of every
`.java` file under `src/main`: it strips string literals out of every `log.info/warn/error/debug(...)`
call (so message text like `"Invalid token"` is never mistaken for a real value) and fails the
build if what's left contains a bare identifier from a fixed list — `password`, `token`, `secret`,
and their common prefixed variants (`rawToken`, `jwtSecret`, …) — used directly rather than through
a `.getSomething()` call. It's deliberately narrow (a name-based check, not a type-aware one — see
the class's own doc comment for its honest limits), which is exactly the "or document it as a
review checklist item" escape hatch the spec allows for this kind of guard.

---

### 3.3 Security headers — what each one stops

**What it is:** A handful of HTTP response headers that tell the *browser*, not the server, to
restrict what the page/response is allowed to do.

**The analogy:** The server can build the safest possible house, but these headers are the
instructions taped to the browser's front door: "don't let anyone frame this house inside another
page," "don't repeat what was said inside this house to the next house you visit," "only load
furniture that came from this house's own address."

**Why we needed it here:** Spring Security already adds a sensible baseline (`X-Content-Type-Options:
nosniff`, a frame-options default) even with zero configuration — but three headers this project
actually needed had no default at all: a real Content-Security-Policy, `Referrer-Policy`, and
`Permissions-Policy`.

**How it works, one header at a time:**

| Header | What it stops | Our value |
|---|---|---|
| `Content-Security-Policy` | A script or style injected from anywhere but this origin (a big chunk of what XSS is *for*) | `default-src 'self'; script-src 'self'; ...; frame-ancestors 'none'` |
| `X-Frame-Options: DENY` | This page (or API response) being embedded in an `<iframe>` on someone else's site — a clickjacking building block | `DENY` |
| `Referrer-Policy: no-referrer` | Leaking the full URL a user came from (which can contain a session id or reset token in the query string) to the next site's server logs | `no-referrer` |
| `Permissions-Policy` | A page embedding this app from ever invoking the camera/microphone/geolocation/payment APIs at all | all four denied |
| `Strict-Transport-Security` (HSTS) | A downgrade attack forcing a later visit back to plain HTTP | `includeSubDomains; preload`, sent only when the request is already HTTPS |

**In our code:** `backend/src/main/java/com/secureleaf/auth/security/SecurityConfig.java:98-121`
(the `.headers(...)` block); `frontend/vercel.json` carries the matching set for the Vercel
deployment, since Spring Security's headers only cover API responses, not the static frontend.

**What breaks without it:** HSTS specifically only ever fires on an already-secure request — over
plain HTTP it's silently absent, which is correct (RFC 6797 §7.2: sending it over HTTP is
meaningless and against the header's own spec) but easy to mistake for "it's not working" when
testing locally. Render terminates TLS in front of the app, so without
`server.forward-headers-strategy: framework` (added to `application-prod.yml`), every request would
arrive at Spring looking like plain HTTP even in production, and HSTS would never fire there
either — see the Gotchas table.

---

### 3.4 GraphQL-specific denial-of-service: depth, complexity, and introspection

**What it is:** Three separate ways a GraphQL API can be asked to do far more work than a REST
endpoint ever could from one HTTP request, because the *caller* composes the query out of the
schema's own building blocks.

**The analogy:** A REST endpoint is a fixed-price meal — `GET /api/products/42` always costs about
the same. GraphQL is an à la carte menu with no limit on how many dishes you can order in one
ticket, or how many times you can order the same dish under different names.

**Why we needed it here:** Nothing in the schema itself limits nesting. `MaxQueryDepthInstrumentation`
caps how many levels deep a query's field selections can nest (10); `MaxQueryComplexityInstrumentation`
caps the total number of selected fields, counting each **alias** as its own field — so
`{ a1: title a2: title a3: title ... }` two hundred times over doesn't dodge the limit just because
it's the same underlying field. Introspection (`__schema`/`__type` — the meta-queries that let a
client discover every field, argument, and type the schema has) is invaluable in development (it's
what powers GraphiQL's autocomplete) and free reconnaissance for an attacker in production.

**How it works:** Both instrumentations are plain `graphql-java` classes, registered as Spring
beans — Spring for GraphQL's autoconfiguration collects every `Instrumentation` bean automatically,
no wiring beyond declaring them. Introspection is a one-line Spring Boot property,
`spring.graphql.schema.introspection.enabled: false`, set only in `application-prod.yml` — Boot
3.3 already has this property built in, unlike structured logging (3.2).

**In our code:** `backend/src/main/java/com/secureleaf/common/config/GraphQlHardeningConfig.java`

**What breaks without it:** `GraphQlHardeningIT` proves both limits by attack, not just by
inspection — it builds a 13-level-deep introspection query (`__type` chained through its own
self-referential `ofType` field) and a 100-alias `categories { id name }` query, and asserts both
get rejected with an error mentioning "depth" or "complexity" respectively. `IntrospectionDisabledIT`
proves the exact property `application-prod.yml` relies on actually blocks a `{ __schema { ... } }`
query when set — see the Gotchas table for why that test can't just activate the literal `prod`
Spring profile.

---

### 3.5 Credential stuffing and throttling

**What it is:** Credential stuffing is an attacker trying many passwords — or the same leaked
password across many accounts — automatically, fast. Throttling slows that down by refusing
further attempts after too many failures in a short window.

**The analogy:** A bank teller who, after five wrong PIN attempts on one card, makes you wait
before trying a sixth — even though the sixth guess might have been the right one.

**Why we needed it here:** SecureLeaf already had exactly this pattern for password-reset requests
(`PasswordResetService`, Phase 7) — this phase applies the same Redis `INCR`+`EXPIRE` recipe to the
higher-value target: the login mutation itself.

**How it works:** `LoginThrottleService` keys its counter by **email + IP**, not either alone.
Email alone would let an attacker lock a *victim* out of their own account on purpose, from a
different IP, turning the anti-abuse feature into a weapon. IP alone is defeated by rotating IPs
and over-punishes a shared IP (a campus NAT, a corporate proxy) for one person's typos.
`AuthService.login` checks the counter *before* even querying the user — a caller already over the
limit gets `RATE_LIMITED` immediately, with no password-check timing to learn from. Every rejected
credential check (unknown email, wrong provider, wrong password) records a failure; a successful
login clears the counter.

**In our code:** `backend/src/main/java/com/secureleaf/auth/service/LoginThrottleService.java`

**What breaks without it:** without email+IP specifically, `LoginThrottleIT`'s
`differentIp_getsItsOwnCounter` test would fail — a scripted attacker guessing one victim's
password from a botnet of rotating IPs would only ever need 5 guesses per IP before switching,
never actually slowed down at all.

---

### 3.6 Fail-fast configuration

**What it is:** Refusing to start the application at all if a specific, checkable precondition
isn't met — rather than starting successfully and failing (or silently misbehaving) later.

**The analogy:** A pilot's pre-flight checklist that physically won't let the plane taxi if one box
is unchecked, instead of taking off and discovering the problem at altitude.

**Why we needed it here:** Every dev-only secret in this project — `JWT_SECRET`,
`DRM_SIGNING_SECRET` (already covered pre-Phase-9 by `DrmConfig`), the two Razorpay secrets, and
the two MinIO credentials — has a working default value committed in `application.yml`, precisely
so `docker compose up` needs zero setup. That convenience is exactly what makes it easy to forget
to override one on a real deployment, and every one of these left at its published, world-readable
default is a full authentication bypass or a free-purchase bug, not a cosmetic mistake.

**How it works:** `ProdSecretsConfig` runs a `@PostConstruct` check, only when
`environment.matchesProfiles("prod")`, comparing each secret's current value against its known dev
default. Any match throws `IllegalStateException` naming every offending variable at once — not
just the first one found — so a deploy failure tells the owner everything wrong in one shot instead
of one failed deploy per secret.

**In our code:** `backend/src/main/java/com/secureleaf/common/config/ProdSecretsConfig.java:53-70`

**What breaks without it:** `ProdSecretsConfigTest` is a plain unit test (no Spring context —
`@PostConstruct` is just a regular method to call directly) proving four cases: every default still
set → throws naming all five; one default remaining → throws naming only that one; every value
real → starts cleanly; any profile other than `prod` → never throws, even with every default still
in place. That last case matters as much as the first: this check must never block local dev, which
runs with every one of these defaults on purpose.

---

### 3.7 Liveness vs. readiness

**What it is:** Two different yes/no questions an orchestrator (Render, Kubernetes, any load
balancer) can ask a running instance. **Liveness**: "is this process still alive, or should you
kill and restart it?" **Readiness**: "should you currently be sending this instance new traffic?"

**The analogy:** A restaurant's kitchen being on fire is a liveness failure — evacuate and restart.
A kitchen that's fine but has run out of a key ingredient is a readiness failure — stop seating new
customers for that dish, but don't evacuate the building.

**Why we needed it here:** A brief Redis or Postgres blip should pull this instance *out of
rotation* — stop routing it new requests — without killing a perfectly healthy JVM process that
would just hit the exact same blip again seconds after restarting.

**How it works:** `management.endpoint.health.probes.enabled: true` turns on Boot's two built-in
health groups. The **readiness** group is scoped deliberately narrow —
`readinessState,db,redis` — not Boot's full default set of every auto-configured `HealthIndicator`.
Boot also auto-registers a `mail` indicator (a real SMTP connectivity check, since
`spring-boot-starter-mail` is on the classpath) that isn't in either group: a broken SMTP
connection is a real problem, worth seeing on the plain `/actuator/health` endpoint, but it isn't a
reason to stop routing *read* traffic — most requests never touch email at all. Scoping readiness
narrowly, on purpose, rather than accepting Boot's "everything" default, is the actual lesson here.
`show-details: when-authorized` keeps the *breakdown* (which specific component failed) private to
an authorized caller; the top-level UP/DOWN status is always public, which is what an anonymous
load balancer actually needs.

**In our code:** `backend/src/main/resources/application.yml:131-155`

**What breaks without it:** `ReadinessIT` proves the DOWN case for real — it starts its own private
Postgres+Redis pair (not the shared suite containers, since this test needs to permanently kill its
Redis mid-test), confirms `/actuator/health/readiness` reports UP, stops that Redis container, and
confirms the same endpoint now reports `503`/`DOWN`. Without a test that actually kills a real
dependency, "readiness works" is an assumption, not a proven fact — the difference matters, because
this exact mechanism is what an orchestrator's routing decision depends on in production.

---

### 3.8 The testing pyramid, and where end-to-end fits

**What it is:** A shorthand for how a healthy test suite is shaped: many fast, narrow unit tests at
the bottom, fewer integration tests in the middle, and very few, slow, broad end-to-end (E2E) tests
at the top — each layer catching what the layer below it structurally cannot.

**The analogy:** Testing a car by (a) bench-testing each part (unit), (b) testing that the engine
and transmission work together on a stand (integration), and (c) actually driving the finished car
around a track (E2E). You'd never *only* drive it around a track — a part failing mid-drive tells
you almost nothing about which part — but skipping the drive entirely means you've never actually
proven the whole car works together.

**Why we needed it here:** every other test in this project — `*Test.java` unit tests,
`*IT.java` Spring Boot integration tests, frontend `*.test.tsx` component tests — proves one layer
in isolation, often against a mock (a fake `PaymentGateway`, an in-memory storage stub, a mocked
GraphQL response). None of them proves that a real browser, hitting a real running frontend, hitting
a real running backend, hitting a real Postgres/Redis, can complete the entire golden path.

**How it works:** `e2e/tests/mvp1-journey.spec.ts` is a single Playwright test: register a creator,
become a creator, upload a real 3-page PDF and wait for the real async pipeline to reach LIVE,
register a buyer, buy through the mock payment gateway, open the secure viewer, and assert the page
renders on a real `<canvas>` with actual non-blank pixels and no `<img>` tag anywhere in the viewer
— the one test in the whole project that never mocks anything below the browser.

**In our code:** `e2e/tests/mvp1-journey.spec.ts`

**What breaks without it:** everything this test exercises could individually pass its own
unit/integration test while the *seams between* them are broken — a GraphQL field renamed on one
side and not the other, a REST endpoint's base URL hardcoded in a way that only works behind a
proxy (see the Gotchas table for exactly this bug, found *by* writing this test), a race between
two effects that only shows up when a real browser runs real React against a real backend's real
timing. A pyramid with a wide integration layer and *zero* E2E tests has never actually been driven
around the track.

---

### 3.9 Requirements traceability

**What it is:** A single document mapping every requirement id to concrete evidence — a specific
test, or a specific file and line — that it's actually implemented, rather than trusting that "the
feature was built in Phase N" is still true.

**The analogy:** An auditor doesn't accept "we definitely paid that invoice" — they want the
specific check number, or the specific bank transaction, that proves it.

**Why we needed it here:** Requirements accumulate across nine phases built by different runs, over
time; nothing structurally *forces* every one of them to still be true today (a refactor could have
silently broken one; a requirement could have been half-built and forgotten). D10 is the forcing
function: writing the matrix means actually finding — or failing to find — real evidence for every
single id.

**How it works:** `docs/release-mvp1.md`. For every requirement, id by id: Done / Partial /
Deferred, plus the test method or `file:line` that proves it, plus honest notes wherever the
evidence is thinner than "Done" would suggest.

**What breaks without it:** without this document, "is MVP1 actually complete" has no answer
stronger than "the phase specs all say so" — which is exactly the kind of claim that erodes quietly
over nine phases of changes nobody re-audits.

---

## 4. Best practices applied

| Practice | What we did | Why it matters | Where |
|---|---|---|---|
| Observability by default, not by request | Correlation id on every request, no opt-in | A production incident is debugged with what's *already* logged, not what you wish you'd added | `CorrelationIdFilter.java` |
| Fail fast on a known-dangerous default | Refuse to start in prod with a dev secret still set | Turns a silent production vulnerability into an immediate, loud deploy failure | `ProdSecretsConfig.java` |
| Defense in depth | Rate limiting (app layer) + password hashing (already existed) + generic error messages (already existed) all independently slow/blunt credential stuffing | No single control has to be perfect | `LoginThrottleService.java` |
| Prefer the platform's built-in mechanism over a custom one | `spring.graphql.schema.introspection.enabled` (a real Boot property) instead of a custom `GraphQlSourceBuilderCustomizer` bean | Less code to maintain, and it's the mechanism every other Spring for GraphQL project already uses | `application-prod.yml` |
| Narrow the blast radius of a health check | Readiness only includes `db`+`redis`, not every auto-configured indicator | A broken, non-critical dependency (SMTP) shouldn't pull a healthy instance out of rotation | `application.yml` |
| A static guard, honestly scoped | `LoggingHygieneTest` documents its own name-based limitation instead of overclaiming | A reviewer trusts a guard more when its blind spots are written down | `LoggingHygieneTest.java` |
| Prove the negative, not just the positive | `ReadinessIT` actually stops a Redis container mid-test | "It reports DOWN when Redis is down" is a claim that needs a broken-Redis test, not an inspection of the YAML | `ReadinessIT.java` |
| End-to-end coverage for the one thing unit tests can't see | One Playwright test drives a real browser through the entire golden path | Integration seams (a hardcoded relative URL, a race in effect timing) only show up when everything runs together for real | `e2e/tests/mvp1-journey.spec.ts` |

---

## 5. What does what — file map

| File | Responsibility |
|---|---|
| `backend/.../common/web/CorrelationIdFilter.java` | First filter in the chain — reads/mints the correlation id, puts it in MDC, echoes it on the response |
| `backend/.../common/config/MdcTaskDecorator.java` | Propagates MDC onto `@Async` pool threads |
| `backend/src/main/resources/logback-spring.xml` | Plain console logs (dev/test) vs. JSON logs (prod) |
| `backend/.../common/config/GraphQlHardeningConfig.java` | Max query depth (10) / complexity (200) instrumentation beans |
| `backend/.../auth/service/LoginThrottleService.java` | Redis-backed 5-failures-per-15-minutes login counter, keyed by email+IP |
| `backend/.../common/config/ProdSecretsConfig.java` | Refuses to start in prod with a dev-default JWT/Razorpay/MinIO secret |
| `backend/.../auth/security/SecurityConfig.java` | CORS (now configurable via `CORS_ALLOWED_ORIGINS`), correlation/JWT filter ordering, security headers |
| `backend/.../common/config/AppProperties.java` | `app.frontend-base-url` and the new `app.cors-allowed-origins` |
| `frontend/vercel.json` | CSP/frame/referrer/permissions headers for the Vercel deployment |
| `frontend/src/lib/correlationId.ts` | Generates a per-request id; appends `(Reference: …)` to a user-facing error message |
| `frontend/src/graphql/apolloClient.ts` | Sends the correlation id on every GraphQL request; surfaces it in a failed request's error message |
| `frontend/src/lib/restClient.ts` | Same, for REST; `baseURL` now configurable via `VITE_API_URL` (was hardcoded `/api`) |
| `e2e/tests/mvp1-journey.spec.ts` | The MVP1 golden-path Playwright test |
| `docs/deployment.md` | The Render/Supabase/Vercel/Upstash runbook |
| `docs/release-mvp1.md` | The requirements traceability matrix |

**Request trace — a request that fails partway through:**
1. `CorrelationIdFilter` mints (or accepts) an id, puts it in MDC →
2. The request reaches a resolver/controller; every `log.info(...)` on the way carries the id →
3. Something throws; `GlobalGraphQlExceptionHandler`/`GlobalRestExceptionHandler` maps it to a
   structured error →
4. The frontend's `errorLink`/`restClient` interceptor appends `(Reference: <id>)` to the message
   shown in the page's existing inline error banner →
5. The user reports "Reference: abc-123"; the owner greps production JSON logs for that exact
   string and sees every line, across every layer, that request touched.

---

## 6. Design decisions and trade-offs

### Decision: append the correlation id to existing inline error banners, not a new toast system

- **Alternatives considered:** build a global toast notification component; do nothing and only
  show the id in the browser console.
- **Why we chose this:** the spec's wording ("shows it in error toasts") describes the *outcome*
  (a user has something to quote), not a specific UI widget — and this app has no toast library at
  all (an explicit, pre-existing comment in `LoginPage.tsx` says so). Mutating the propagated error's
  `.message` in one place (the Apollo `errorLink` and the axios response interceptor) means every
  existing inline error banner across the app — `LoginPage`, `RegisterPage`, `UploadProductPage`,
  `ResetPasswordPage`, `BecomeCreatorPage` — picks up the reference automatically, with zero
  per-page changes.
- **What we gave up:** a toast is more visible than an inline banner tied to a specific form; a
  page-level error with no visible form (rare in this app) wouldn't show a reference at all today.
- **When we would revisit:** if this app ever adds a global toast system for other reasons, route
  the correlation id through it too — the mechanism (append it to the message) stays the same either
  way.

### Decision: a name-based static test for logging hygiene, not a type-aware one

- **Alternatives considered:** no automated check at all (a documented review checklist item, which
  the spec explicitly allows); a full static-analysis tool (e.g. a custom Error Prone/Checkstyle
  rule) that understands types, not just identifier names.
- **Why we chose this:** the spec calls this "cheap to do" — a regex-based scan that runs in
  milliseconds as part of the existing test suite, with no new tooling dependency, catches the
  common, careless mistake (`log.info("...", password)`) that's actually the most likely one to
  happen by accident.
- **What we gave up:** it's a name-based heuristic. A secret held in a variable named something
  unlisted (`creds`, `pw`) slips through entirely; it also can't tell that `tokenHash` (a SHA-256
  digest, safe to log) is fine while `rawToken` isn't, except by having both names on file. The
  test's own doc comment says this outright.
- **When we would revisit:** if a real incident is ever traced back to a logged secret this test
  didn't catch, that's the signal to add the specific missed identifier to the list — or to invest
  in a type-aware tool instead.

### Decision: readiness includes only `db`+`redis`, not Boot's full default indicator set

- **Alternatives considered:** accept Boot's default (every auto-configured `HealthIndicator`,
  including `mail` and `diskSpace`) as the readiness group.
- **Why we chose this:** readiness exists to answer one question — "can this instance currently
  serve the traffic a load balancer would send it?" A broken SMTP connection doesn't stop this
  instance from serving `products`/`me`/`viewerPageUrl` — the overwhelming majority of traffic. Diagnosed
  live, during this phase's own manual verification: a real `mail` health-check failure (no SMTP
  server reachable) made the plain, unscoped `/actuator/health` endpoint report DOWN while the
  scoped `readiness` group correctly stayed UP — direct proof the narrower scoping was the right
  call, not just a theoretical nicety.
- **What we gave up:** a genuinely broken mail server is now invisible to whatever's watching
  *readiness* specifically — it would need to watch the plain `/actuator/health` endpoint (or its
  own dashboard) to notice, and that endpoint's `show-details: when-authorized` means an anonymous
  caller still can't see *which* indicator failed.
- **When we would revisit:** if email delivery becomes load-bearing for a synchronous, user-facing
  flow (it currently isn't — every email send in this codebase is `@Async`, after-commit), readiness
  should include it too.

---

## 7. Interview questions

### Beginner

**Q: What is a correlation id and why do you need one?**
A: It's one value generated at the start of a request and included in every log line written while
handling it, so you can search your logs for that one value and see the request's entire path
through the system — which service touched it, in what order, what it logged — instead of trying
to reconstruct that from timestamps and guesswork.

**Q: What's the difference between liveness and readiness?**
A: Liveness asks "is the process alive, or should it be restarted?" Readiness asks "should traffic
be routed to it right now?" A process can be alive but not ready — for example, if its database
connection just dropped — and you want the orchestrator to stop sending it requests without
killing and restarting a perfectly healthy JVM.

### Intermediate

**Q: MDC is thread-local. Why does that matter for `@Async` methods, and how do you fix it?**
A: Because `@Async` hands the work to a completely different, pre-existing thread from a thread
pool — a thread that was never part of this request's call stack and has no idea what's in this
request's MDC. Without intervention, every log line from inside that async method has no
correlation id. The fix is a `TaskDecorator` that copies the calling thread's MDC onto the pool
thread right before the task runs, and restores whatever was there before once it finishes — so the
*next* unrelated task on that pool thread doesn't inherit this request's id either.

**Q: Why key a login-throttle counter by email+IP instead of email alone?**
A: Email alone lets an attacker weaponize the throttle itself — deliberately fail five logins
against a victim's account from an IP the victim doesn't use, locking the real owner out. Email+IP
narrows the counter to "this specific source, guessing this specific account," which is the actual
threat the control exists to slow down.

**Q: Why does introspection matter for GraphQL security, and how is it different from the
depth/complexity limits?**
A: Introspection is a *meta*-query — `__schema`/`__type` — that lets any caller ask the API to
describe its own entire schema: every type, every field, every argument. It's not a
denial-of-service risk like depth/complexity (those cap how *expensive* one query can be);
introspection is a reconnaissance risk — it hands an attacker the exact shape of every mutation and
query without ever needing the source code. That's why it's a separate, binary on/off switch, not a
numeric limit.

### Advanced / follow-up probes

**Q: Your readiness check for Redis is proven by actually stopping a Redis container mid-test.
Why not just assert the YAML configuration is correct?**
A: Because "the YAML says readiness includes redis" and "readiness actually reports DOWN when Redis
is genuinely unreachable" are different claims — the second one depends on Spring Boot's
auto-configured `RedisHealthIndicator` actually doing a live `PING`, on the HTTP status mapping
actually returning 503, and on nothing else in the request path masking the failure. A test that
only inspects configuration can't catch any of those; only a test that breaks the real dependency
and checks the real HTTP response can.

**Q: The spec asked for a fully automated CI workflow for the E2E test, but you delivered it as a
`*.example` file instead. Walk through the reasoning.**
A: The phase's own automation constraint forbids editing anything under `.github/workflows/` — an
automated run isn't allowed to change what CI actually executes, only a human merging a reviewed
change can. Delivering `docs/ci/e2e.yml.example`, a file the owner copies into place themselves, respects
that boundary while still producing a complete, ready-to-use workflow — the content is fully done;
only the "turn it on" step is deliberately left to a human.

**Q: You found that `restClient`'s hardcoded `/api` base URL would break on a real Vercel+Render
deployment. How did you find that, and why didn't the existing test suite catch it?**
A: I found it by actually trying to deploy — well, by trying to *run* the whole stack together for
the E2E test, which is precisely the kind of integration seam a unit or mocked-integration test
structurally cannot see: every existing frontend test either mocks the network layer or runs
against Vite's dev proxy, where `/api` happens to resolve correctly. Only a test (or a real
deployment) that removes that proxy and calls the backend as a genuinely separate origin exposes
that the base URL needs to be absolute and configurable. That's the concrete argument for keeping
one E2E test in the suite even though it's slow and this project otherwise leans on faster,
narrower tests.

### "Tell me about a bug you fixed"

**Q: Tell me about a bug you found while building this.**
A: While first trying to run the new end-to-end Playwright test against a real stack, the final
assertion — "the viewer's canvas has real, non-blank pixels" — failed consistently, even though
server logs showed the tile endpoint returning a valid, correctly-sized PNG every time. My first
theory was a real app bug: maybe the viewer session was somehow racing itself. I added console and
network logging to the test and confirmed the fetch genuinely succeeded with a 200 and the right
content-type — the bytes were reaching the browser fine. The actual bug was in my *test*, not the
app: a `<canvas>` element defaults to a non-zero size (300×150) the instant it's created, before any
image is ever drawn to it. My readiness check polled for "canvas width and height are greater than
zero" as a proxy for "the tile has loaded" — but that condition was already true from the very first
render, long before the async fetch-decode-draw sequence (`fetch` → `blob()` → `createImageBitmap`
→ `drawImage`) had actually completed. The fix was to poll for the *real* signal — an actual
non-transparent pixel in the canvas's pixel data — instead of a proxy that happened to be true too
early. The lesson: a readiness check needs to test the thing you actually care about, not something
merely correlated with it that can be true for an unrelated reason.

---

## 8. Gotchas and bugs we hit

| Symptom | Root cause | Fix | Lesson |
|---|---|---|---|
| `SecurityConfig` threw `IllegalArgumentException: The Filter class ... does not have a registered order` at startup | `.addFilterBefore(correlationIdFilter, JwtAuthenticationFilter.class)` was called *before* `JwtAuthenticationFilter` itself had been registered in the chain — Spring Security's internal filter-order registry only knows about a custom filter class once it's already been added via an earlier `addFilterBefore/After` call in the same builder (or is one of Spring Security's own standard filters) | Register `jwtAuthenticationFilter` first (anchored to the well-known `UsernamePasswordAuthenticationFilter`), *then* register `correlationIdFilter` anchored to the now-known `JwtAuthenticationFilter` | Order of `addFilterBefore`/`addFilterAfter` calls matters when chaining custom filters off each other — the reference filter must already be "known" to the chain being built |
| `frontend.Dockerfile` failed every build with `"/nginx/nginx.conf": not found` | `COPY ../nginx/nginx.conf ...` tried to reach outside the Docker build context (`frontend/`) — Docker can never `COPY` a path from outside its build context, no matter how the Dockerfile is written | Rebuilt the image to use the **repository root** as its build context, prefixing every `COPY` with the actual subdirectory it comes from (`frontend/...`, `infra/nginx/...`) | A multi-directory Docker image needs a build context that's a common ancestor of every directory it copies from — this had silently never actually been buildable with the context the existing (unrelated, un-editable-by-this-phase) CI workflow uses |
| The E2E journey's final assertion failed even though the tile endpoint returned a correct 200 PNG every time | The test polled for "canvas width/height > 0" as a proxy for "the tile has rendered," but a `<canvas>` defaults to a non-zero size (300×150) before anything is ever drawn to it — the poll was satisfied instantly, before the async fetch→decode→draw sequence had actually completed | Poll for an actual non-transparent pixel in the canvas's real pixel data instead | A readiness check has to observe the thing you actually care about — a merely-correlated signal (non-zero size) can be true for a completely unrelated reason (a browser default) |
| MinIO's official image (`quay.io/minio/minio`) could not be pulled while manually verifying this phase in a sandboxed environment | The registry `quay.io` was unreachable/unauthorized in that specific sandbox (`docker.io` worked fine); separately, MinIO's community binary releases (`dl.min.io`) now return `410 Gone` — the open-source distribution channel was recently discontinued | Manually substituted the project's own test-only `InMemoryStorageService` on the runtime classpath for this one verification pass, and stood up Postgres/Redis as plain `docker run` containers instead of `docker compose up` (which needs MinIO) | Don't assume a documented `docker compose up` workflow is reachable from every environment — a normal GitHub Actions runner has open internet access and won't hit this; a locked-down sandbox might. Worth flagging explicitly rather than silently declaring the E2E test "couldn't be run" |
| `login`'s success log line (`"User logged in: id={}, email={}"`) and three siblings logged an email address | Pre-Phase-9 code; nobody had audited existing `log.info` calls against the "never log emails/tokens/secrets" rule until this phase's `LoggingHygieneTest` was being designed | Removed the email argument from all four call sites (`AuthService` ×4, plus `AdminBootstrapService`); added a `userId` field to `PasswordResetMailRequestedEvent` so its one remaining email-address log line could be replaced with an id too | A hygiene rule is only as good as actually auditing existing code against it once, not just applying it to new code going forward |

---

## 9. New vocabulary

| Term | One-line meaning |
|---|---|
| Correlation id | One value attached to every log line a single request produces, across every layer and thread it touches |
| MDC (Mapped Diagnostic Context) | SLF4J's per-thread key/value map that every log statement on that thread reads automatically |
| Structured logging | One JSON object per log line, instead of free text — built for machines to filter/index, not just humans to read |
| GraphQL introspection | The `__schema`/`__type` meta-queries that let a client discover a GraphQL API's entire schema |
| Query complexity | A numeric cost assigned to a GraphQL query, usually one point per selected field (aliases counted separately) |
| Query depth | How many levels deep a GraphQL query's field selections nest |
| Credential stuffing | Automated, high-volume password guessing against one or many accounts |
| Fail-fast configuration | Refusing to start at all if a known-dangerous precondition (e.g. a dev secret in prod) is met, rather than starting and failing later |
| Liveness probe | "Is this process alive, or should it be restarted?" |
| Readiness probe | "Should traffic currently be routed to this instance?" |
| Testing pyramid | Many fast unit tests, fewer integration tests, very few slow end-to-end tests — each layer catching what the one below structurally can't |
| End-to-end (E2E) test | A test that drives the real, fully assembled system (here: a real browser against a real running frontend, backend, database, and cache) |
| Requirements traceability matrix | A document mapping every requirement id to the specific evidence (test or code) proving it's implemented |
| HSTS (HTTP Strict Transport Security) | A header telling the browser to only ever connect to this origin over HTTPS from now on |
| Permissions-Policy | A header disabling specific browser APIs (camera, microphone, geolocation, payment) for the page |

---

## 10. If I had to defend this in a code review

The strongest points: every claim in this phase is backed by a test that proves the *failure mode*,
not just the happy path — `ReadinessIT` actually kills a Redis container, `GraphQlHardeningIT`
actually sends an over-deep and an over-complex query and checks the rejection, `ProdSecretsConfigTest`
proves the validator is silent everywhere except the exact profile it's meant to guard, and the E2E
test genuinely exercises the entire golden path with nothing mocked below the browser. The
MinIO-unavailable gotcha and the frontend Dockerfile bug were both found and fixed only *because*
real, honest verification was attempted instead of stopping at "the code should work."

The weakest point, and the one I'd fix first given more time: `LoggingHygieneTest`'s name-based
check is real protection against the common, careless mistake, but it's exactly as strong as its
hardcoded identifier list and no stronger — a secret held in a variable spelled differently slips
through entirely, silently. I'd want either a periodic manual audit of new `log.*` call sites (a
review checklist item, which the spec explicitly allows as the fallback here) or, given more time,
a genuinely type-aware static analysis pass that flags any value assignable from a
`@ConfigurationProperties`-bound secret field reaching a logging call, regardless of what the local
variable happens to be named.
