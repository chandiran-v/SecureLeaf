# SecureLeaf — Deployment Runbook (MVP1)

> Phase 9, D9. Production target: **Render** (backend), **Supabase** (Postgres + S3-compatible
> object storage), **Vercel** (frontend), **Upstash** (Redis). This is the owner's job — it needs
> accounts and secrets an automated run can't hold — this document gets everything ready for it.

---

## 0. Before you start — read this

**MVP1 ships with no real payment gateway.** `CommerceConfig` (`backend/src/main/java/com/secureleaf/commerce/CommerceConfig.java`)
refuses to start with any `payment.gateway.provider` other than `mock` — there is no `RazorpayGateway`
implementation yet. To deploy MVP1 at all today you must set `PAYMENT_GATEWAY=mock` in production,
which means **every checkout can be marked "paid" for free**. The app logs a loud `WARN` on every
startup while this is true. This is a deliberate, documented MVP1 limitation (see
`docs/release-mvp1.md`), not an oversight — do not process real transactions against this
deployment until a real gateway exists.

---

## 1. Environment variables

Every one of these has a **working default for local Docker Compose** in `backend/src/main/resources/application.yml`
— that's what makes `docker compose up` need zero setup, and it's also exactly why it's easy to
forget to override one in production. `ProdSecretsConfig`, `DrmConfig` and `CommerceConfig` refuse
to start in the `prod` profile if a secret is still at its dev default (D6) — a failed deploy with
a clear error message is safer than starting with a known secret.

| Variable | Where it's used | Where to get it | Prod note |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | Boot profile selection | — | Set to `prod` on Render |
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` | `spring.datasource.*` | Supabase → Project Settings → Database → Connection string | Use the **pooled** (pgBouncer, port 6543) connection for the app; Flyway migrations run fine through it |
| `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` | `spring.data.redis.*` | Upstash → your database → Details (TCP endpoint, not the REST API) | Upstash requires TLS — see §2 note below |
| `MINIO_ENDPOINT`, `MINIO_ACCESS_KEY`, `MINIO_SECRET_KEY` | `minio.*` (raw PDFs + tiles + thumbnails) | Supabase → Project Settings → Storage → S3 Connection (S3-compatible endpoint, access key, secret key) | **Must** differ from the dev defaults (`secureleaf_minio_user`/`secureleaf_minio_pass`) — `ProdSecretsConfig` refuses to start otherwise |
| `JWT_SECRET` | Signs every access/refresh token | Generate: `openssl rand -base64 32` | **Must** differ from the dev default — fails fast otherwise (D6) |
| `DRM_SIGNING_SECRET` | HMAC-signs every tile URL (`TileUrlSigner`) | Generate: `openssl rand -base64 32` | **Must** differ from the dev default — fails fast otherwise (already enforced pre-Phase-9 by `DrmConfig`) |
| `GOOGLE_CLIENT_ID` | Verifies Google Sign-In tokens | Google Cloud Console → APIs & Services → Credentials → OAuth client ID | Leave empty to disable Google sign-in entirely |
| `PAYMENT_GATEWAY` | Which `PaymentGateway` bean loads | — | **Must be `mock`** until a real gateway ships — see §0 |
| `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, `RAZORPAY_WEBHOOK_SECRET` | Mock gateway's signature shapes | Any value while `PAYMENT_GATEWAY=mock` | `KEY_SECRET`/`WEBHOOK_SECRET` **must** differ from the dev defaults — fails fast otherwise (D6) |
| `ADMIN_EMAILS` | Comma-separated emails granted ADMIN at startup/registration | You decide | Empty by default — nobody is an admin until set |
| `FRONTEND_BASE_URL` | Builds emailed links (e.g. password reset) | Your Vercel URL | e.g. `https://secureleaf.vercel.app` |
| `CORS_ALLOWED_ORIGINS` | Which browser origins may call this API | Your Vercel URL(s), comma-separated | See §3 — this is new in Phase 9 |
| `MAIL_HOST`, `MAIL_PORT`, `MAIL_USER`, `MAIL_PASS` | SMTP for password-reset/purchase emails | Any SMTP provider (SendGrid, Postmark, Mailgun, …) | Dev uses Mailpit; nothing is provided for prod — pick one |
| `NOTIFICATION_EMAIL_ENABLED` | Gate on non-critical notification emails | — | `true`/`false` |

**Frontend** (Vercel project → Settings → Environment Variables):

| Variable | Needed when | Value |
|---|---|---|
| `VITE_GOOGLE_CLIENT_ID` | Google Sign-In is enabled | Same client id as `GOOGLE_CLIENT_ID` above |
| `VITE_GRAPHQL_URL` | **Always, on Vercel** — see §3 | `https://<your-render-backend>.onrender.com/graphql` |
| `VITE_API_URL` | **Always, on Vercel** — see §3 | `https://<your-render-backend>.onrender.com/api` |

---

## 2. Build and start commands

### Backend (Render — Docker runtime)

`infra/docker/backend.Dockerfile` already builds and runs cleanly (verified as part of Phase 9):

- **Build context**: `backend/` (this Dockerfile's `COPY pom.xml .` / `COPY src ./src` are relative to that directory, unlike the frontend one below — don't point this at the repo root).
- **Dockerfile path**: `infra/docker/backend.Dockerfile`
- **Start command**: none needed — the image's `ENTRYPOINT` already runs `java -jar app.jar` with container-aware memory flags.
- Set every backend env var from §1 in Render's environment settings, plus `PORT` if Render requires it explicitly (Spring Boot listens on `8080`; Render's Docker runtime auto-detects the exposed port from the Dockerfile's `EXPOSE 8080`).

**Redis + TLS**: Upstash's TCP endpoint requires TLS. Spring Data Redis's Lettuce client needs
`spring.data.redis.ssl.enabled=true` for that — this isn't in `application.yml` (dev's local Redis
has no TLS) — set it as an extra env var override: `SPRING_DATA_REDIS_SSL_ENABLED=true`.

### Backend (alternative — Render native Java runtime, no Docker)

- **Build command**: `cd backend && ./mvnw -B -DskipTests package`
- **Start command**: `java -jar backend/target/secureleaf-backend-*.jar --spring.profiles.active=prod`

### Alternative: fully self-hosted via Docker Compose

Both `infra/docker/backend.Dockerfile` and `infra/docker/frontend.Dockerfile` build cleanly
(verified as part of Phase 9 — the frontend one didn't, before: it tried to `COPY ../nginx/nginx.conf`,
a path outside a `frontend/`-scoped build context, which Docker always rejects; it's fixed to build
from the **repository root** instead — `docker build -f infra/docker/frontend.Dockerfile -t secureleaf-frontend .`
— see the comment at the top of that file). In this all-in-one deployment shape, `infra/nginx/nginx.conf`
proxies the frontend and backend behind one origin, so §3's CORS/absolute-URL concerns don't apply —
only the Vercel+Render split needs them.

### Frontend (Vercel)

- **Framework preset**: Vite
- **Root directory**: `frontend`
- **Build command**: `npm run build` (default)
- **Output directory**: `dist` (default)
- `frontend/vercel.json` (added in Phase 9, D3) ships the CSP/frame/referrer/permissions headers automatically — nothing to configure.

### Flyway on boot

No separate migration step — `spring.flyway.enabled: true` (`application.yml`) runs every pending
migration in `backend/src/main/resources/db/migration/` automatically on application startup,
before Tomcat starts accepting requests. A failed migration fails the deploy (the app won't come
up) rather than serving traffic against a half-migrated schema.

---

## 3. CORS origins — why this matters here specifically

The Docker Compose deployment (`infra/nginx/nginx.conf`) proxies `/graphql` and `/api` to the
backend container, so the browser only ever talks to **one origin** — CORS never comes into play.
**Vercel + Render is different**: the frontend and backend are genuinely different origins, so:

- The backend must list the frontend's exact origin in `CORS_ALLOWED_ORIGINS` (comma-separated, no
  spaces, no trailing slash) — `SecurityConfig`'s CORS bean reads this via `AppProperties`
  (Phase 9, D3/D9; previously hardcoded to the two local dev ports).
- The frontend must call the backend by its **full URL**, not a relative path — set
  `VITE_GRAPHQL_URL` and `VITE_API_URL` (Phase 9, D9 — `restClient.ts` previously hardcoded
  `/api`, which would silently 404 against Vercel's own domain with no proxy in front of it).

Get both wrong in opposite ways (frontend calling absolute URLs, backend not allowing that origin)
and every request fails at the CORS preflight with no useful error in the app itself — only in the
browser's console and the Network tab.

---

## 4. How to rotate a secret

1. Generate the new value (e.g. `openssl rand -base64 32` for `JWT_SECRET`/`DRM_SIGNING_SECRET`).
2. Set it in Render's environment variables and redeploy.
3. **What breaks, per secret, while both old and new instances are briefly live during a rolling
   deploy:**
   - `JWT_SECRET`: every access/refresh token issued under the old secret stops validating the
     instant the new secret is live — every logged-in user is signed out. There is no graceful
     dual-secret window in this codebase; plan a rotation for low-traffic hours.
   - `DRM_SIGNING_SECRET`: every signed tile URL issued in the last 30 seconds (its TTL) stops
     validating — at most a handful of in-flight page loads see one failed tile fetch, which the
     viewer's own retry button recovers from.
   - `RAZORPAY_KEY_SECRET` / `RAZORPAY_WEBHOOK_SECRET`: not used at all while `PAYMENT_GATEWAY=mock`
     — rotate freely.
   - `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY` (Supabase Storage credentials): rotate in Supabase
     first, update Render's env vars, then redeploy — there's no fallback if these are wrong, every
     upload/tile request fails until fixed.
4. Never rotate `DB_PASSWORD` or Redis credentials without first updating them at the provider
   (Supabase/Upstash) — this app never manages those credentials itself.

---

## 5. Rollback

Render keeps previous deploys: **Render dashboard → your service → Deploys → pick a prior
successful deploy → Rollback**. Two things to know before you do:

- **Flyway migrations do not roll back automatically.** If the deploy you're rolling back *from*
  included a new migration, rolling the *code* back does not undo the *schema* change. Check
  `backend/src/main/resources/db/migration/` for anything added since the last good deploy; a
  destructive migration (dropping/renaming a column the old code still reads) needs a hand-written
  down-migration before the code rollback is safe.
- Vercel: **Vercel dashboard → your project → Deployments → pick a prior deployment → Promote to
  Production** — this one is instant and has no schema-coupling concern (the frontend has no
  database).

---

## 6. Post-deploy smoke checklist

Run through this after every production deploy, in order:

1. `GET https://<backend>/actuator/health/readiness` returns `{"status":"UP"}` (public; see D7).
2. Register a test account through the real frontend URL; confirm the JWT comes back and `me`
   resolves.
3. `docs/ci/e2e.yml.example`'s journey, run manually against production once (register → become
   creator → upload → LIVE → buy → view) — the single strongest signal that every layer is wired
   correctly, since it's the same test Phase 9 (D8) wrote for exactly this purpose. Use disposable
   test accounts; don't leave real payment records lying around even under the mock gateway.
4. Open the browser's Network tab on the real frontend origin and confirm: no CORS errors, the
   CSP/`X-Frame-Options`/`Referrer-Policy`/`Permissions-Policy` headers are present on API
   responses (`curl -I` also works), and `Strict-Transport-Security` is present (confirms
   `server.forward-headers-strategy: framework` is correctly reading Render's `X-Forwarded-Proto`).
5. Check the backend's log stream for the very first few lines — confirm JSON structured logs
   (Phase 9, D2) are showing up correctly for whatever log aggregator you've pointed at Render's
   log stream, not plain text.
6. Confirm the loud `PAYMENT_GATEWAY=mock` warning is present in the startup log — if it's
   *missing*, something is misconfigured (or a real gateway shipped and this document is stale).
