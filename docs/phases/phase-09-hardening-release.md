# Phase 09 — Hardening & release readiness (MVP1 finish line)

> Spec for an unattended run. Decisions are numbered (D1…) and referenced from code comments.
> Requirements: the **Non-Functional Requirements** table in `docs/requirements.md`: observability, security, and reliability.
> Depends on: Phase 08. After this phase is merged, the owner releases MVP1 (merges it to `main`).

## Context

The features are complete. This phase makes MVP1 **safe to deploy and easy to operate**, and produces
the evidence that every MVP1 requirement is met. The deployment itself is the owner's job (it needs
accounts and secrets). This phase gets everything ready for it.

> **Automation constraint:** automated runs may not edit `.github/`. Anything that needs a workflow
> change is delivered as an `*.example` file under `docs/ci/`, with instructions for the owner.

## Decisions

- **D1 — Correlation IDs (NFR observability).**
  - A servlet `OncePerRequestFilter`, ordered first, reads `X-Correlation-Id` or generates a UUID. It puts it in the SLF4J MDC as `correlationId` and echoes it in the response header. It clears the MDC in `finally`.
  - `@Async` executors propagate the MDC through a `TaskDecorator`.
  - The frontend sends a per-request id from both the Apollo link and `restClient`, and shows it in error toasts ("Reference: …") so a support request can be traced.
  - The Phase 05A viewer access log uses the same id.
- **D2 — Structured logs.**
  - The `prod` profile logs JSON using Spring Boot's built-in structured logging (`logging.structured.format.console: ecs`). No new dependency if the Boot version supports it; otherwise add `logstash-logback-encoder`, and say which in the note.
  - Dev keeps readable logs.
  - Log **one** INFO business event each for upload accepted, processing finished, order completed, and viewer session started, with ids only. **Never log emails, tokens or signatures.** Add a test or ArchUnit-style check that fails if a log statement concatenates a field named `password`, `token` or `secret`, if that's cheap to do. Otherwise document it as a review checklist item.
- **D3 — HTTP security headers.** Add them to the Spring Security config:
  - `Content-Security-Policy` suitable for the API
  - `X-Frame-Options: DENY`, so the viewer can't be embedded (framing attacks)
  - `Referrer-Policy: no-referrer`
  - `Permissions-Policy`
  - HSTS in `prod`

  For the frontend, provide `frontend/vercel.json` with a CSP (`default-src 'self'`; `connect-src` for the API origin; `img-src 'self' blob: data:`) plus the same frame and referrer headers.
- **D4 — GraphQL hardening.**
  - Max query depth 10 and max complexity 200 (`MaxQueryDepthInstrumentation` / `MaxQueryComplexityInstrumentation`).
  - Introspection disabled in `prod`, and GraphiQL disabled in `prod` (verify it already is).
  - Tests prove that an over-deep query is rejected.
- **D5 — Login throttling.** At most 5 failed logins per email+IP per 15 minutes (Redis counter). The next attempt gets `RATE_LIMITED` (that error code already exists). A successful login resets the counter. This is not the MVP2 Bucket4j work; it's a small targeted guard against credential stuffing.
- **D6 — Fail-fast production config.** In `prod`, refuse to start if any secret still has its dev default: `JWT_SECRET`, `DRM_SIGNING_SECRET`, the payment secrets, MinIO keys. Use a `@ConfigurationProperties` validator or `ApplicationRunner` with a clear message. Unit-test the validator.
- **D7 — Health and readiness.** Enable the actuator liveness and readiness probes. Readiness includes the DB and Redis. `/actuator/health/readiness` is public, and the details stay private.
- **D8 — End-to-end smoke test (Playwright).** New folder `e2e/`, with its own `package.json`. One journey:
  1. register a creator
  2. become a creator
  3. upload `e2e/fixtures/sample.pdf` (generate a small 3-page PDF in the repo)
  4. wait for LIVE
  5. register a buyer and buy (mock gateway, success)
  6. open the viewer and assert the canvas has non-blank pixels and no `<img>` shows the page

  It runs against the docker-compose stack (`npm run e2e` after `docker compose up`). Deliver `docs/ci/e2e.yml.example` (a GitHub Actions workflow the owner can copy into `.github/workflows/`). If the Docker stack can't run inside the automated job, write and type-check the tests and say plainly in the PR that they were not executed.
- **D9 — Deployment runbook.** Write `docs/deployment.md` for Render (backend), Supabase (Postgres + S3-compatible storage), Vercel (frontend) and Upstash (Redis):
  - every env var, with where to get it
  - build and start commands
  - Flyway on boot
  - CORS origins
  - how to rotate secrets
  - rollback steps
  - a post-deploy smoke checklist

  Verify that the Dockerfiles in `infra/docker/` build.
- **D10 — Requirements traceability matrix.** Write `docs/release-mvp1.md`. For **every** MVP1 requirement ID, record: status (Done / Partial / Deferred), the evidence (test class + method, or file:line), and notes. Anything Partial or Deferred must say why and where it will be handled. Include known limitations and the release checklist the owner follows:
  1. merge
  2. tag `v1.0.0`
  3. deploy
  4. smoke test
  5. create the MVP2 branch

## Acceptance criteria
1. Every response has an `X-Correlation-Id` header. A supplied id is echoed back. The id appears in logs from an `@Async` task (captured-log test).
2. The security headers are present on API responses (MockMvc test).
3. An over-deep or too-complex GraphQL query is rejected. Introspection is disabled when the `prod` profile is active.
4. The 6th failed login in the window gets `RATE_LIMITED`. A success resets the counter.
5. The prod config validator rejects dev defaults and accepts real values.
6. The readiness endpoint reports UP with DB+Redis, and DOWN when Redis is stopped (Testcontainers `stop()` in an isolated test).
7. The `e2e/` project type-checks. The journey passes locally, or the PR states clearly that it was not executed and why.
8. `docs/deployment.md` and `docs/release-mvp1.md` exist, and the matrix covers every MVP1 ID.

All existing tests stay green.

## Out of scope
- Real deployment.
- A real payment gateway.
- Bucket4j and tile rate limits (Phase 11).
- Metrics dashboards (Phase 10).

## Learning note
Create `docs/learning-notes/phase-09-hardening-release.md`. Headline topics:
- correlation IDs and MDC propagation across threads
- structured logging, and what never to log
- security headers (what each one stops)
- GraphQL DoS (depth/complexity, introspection)
- credential stuffing and throttling
- fail-fast configuration
- liveness vs readiness
- the testing pyramid and where E2E fits
- requirements traceability

Also update the learning-notes README with a "MVP1 complete" summary, and update `quick-reference.md` with an MVP1 "60-second tour".
