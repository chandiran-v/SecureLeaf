# SecureLeaf — Deployment Runbook (Oracle Always Free server)

> Phase 09D. Production = **one Oracle Cloud Always Free ARM server** (`VM.Standard.A1.Flex`,
> 2 OCPU / 12 GB, Ubuntu 24.04 aarch64) running `docker compose`: Caddy (TLS + the React app) +
> backend + Postgres + Redis + MinIO. Why: [ADR 0001](adr/0001-hosting-oracle-always-free.md).
> This is the owner's job (it needs accounts and secrets an automated run can't hold) — this
> document and `infra/prod/` get everything ready for it. Day-2 operations: [`ops/runbook.md`](ops/runbook.md).
> The managed-free-tier stack (Render/Supabase/…) survives only as [Appendix A: Plan B](#appendix-a--plan-b-managed-free-tiers).

```
                    Internet
                       │  80/443   (the ONLY published ports)
                 ┌─────▼─────┐
                 │   Caddy   │  TLS (Let's Encrypt) · React app · reverse proxy
                 └─────┬─────┘
        /graphql /api/* /actuator/health
                 ┌─────▼─────┐      internal Docker network only
                 │  backend  ├──► postgres   redis   minio
                 └───────────┘
```

Files: `infra/prod/docker-compose.prod.yml`, `Caddyfile`, `caddy.Dockerfile`, `.env.example`,
`scripts/{setup-server,generate-env,deploy,backup,restore,download-backup,smoke-watermark}.sh`,
`systemd/secureleaf-backup.{service,timer}`, `test/*` (see [§8](#8-testing-the-production-stack-without-a-server)).

---

## 0. Before you start — read this

**Payments run through Razorpay in TEST mode (Phase 09B).** In the `prod` profile the app **refuses to
start** with `PAYMENT_GATEWAY=mock`, with blank Razorpay credentials, or with a `rzp_live_…` key while
`PAYMENT_LIVE_ENABLED` is not `true` (live payments arrive in Phase 18). While the mode is not LIVE the
site shows a "Demo mode — no real money is charged" banner.

**Razorpay dashboard setup (test mode):**
1. *Settings → API Keys* (test mode): generate a key pair → `RAZORPAY_KEY_ID` / `RAZORPAY_KEY_SECRET`.
2. *Settings → Webhooks*: URL `https://<your-host>/api/webhooks/razorpay`, choose a secret →
   `RAZORPAY_WEBHOOK_SECRET`, tick `payment.authorized`, `payment.captured`, `payment.failed`, `refund.processed`.
3. *Settings → Payment Capture*: turn **auto-capture ON** (recommended).
4. Reconciliation runs every 10 minutes and completes paid orders whose webhook was lost.

---

## 1. Create the server (Oracle Cloud console)

1. Sign up at cloud.oracle.com. A card is needed for identity (Always Free resources are not billed).
   **Pick your home region carefully — it is permanent** (Mumbai or Hyderabad for Indian users).
2. *Compute → Instances → Create instance*:
   - **Image:** Canonical Ubuntu 24.04 (aarch64)
   - **Shape:** Ampere `VM.Standard.A1.Flex`, **2 OCPU, 12 GB** (the whole Always Free allowance)
   - **Boot volume:** about **100 GB** (up to 200 GB is free)
   - **SSH keys:** paste your public key (there is no password login)
   - "Out of host capacity" is common: retry later or try another availability domain.
3. Note the **public IP**.
4. **Firewall #1 — the cloud firewall (VCN).** *Networking → Virtual Cloud Networks → your VCN → Security
   Lists → Default → Add Ingress Rules*: source `0.0.0.0/0`, TCP, destination ports **80** and **443**
   (keep 22). If you use a Network Security Group instead, add the same rules there.
   > **The two-firewall trap.** Oracle's Ubuntu images ALSO ship host `iptables` rules that allow only
   > port 22 and then `REJECT` everything. Opening the VCN alone changes nothing — packets reach the VM
   > and are rejected. `setup-server.sh` inserts ACCEPT rules for 80/443 *before* that REJECT and
   > persists them. If the site is unreachable, check **both**.
5. Optional but wise: reserve the public IP (*Networking → Reserved public IPs*) so it survives a rebuild.

## 2. DuckDNS (free hostname)

1. Sign in at duckdns.org, create a subdomain (e.g. `secureleaf`) → `secureleaf.duckdns.org`.
2. Set its IP to the server's public IP. That name is your `SITE_HOST`; Caddy gets the Let's Encrypt
   certificate for it automatically on first start (needs ports 80 and 443 open — see the trap above).

## 3. Run the setup script (once)

```bash
ssh ubuntu@<server-ip>
sudo apt-get update && sudo apt-get install -y git
sudo git clone https://github.com/<you>/SecureLeaf.git /opt/secureleaf     # or let the script clone: REPO_URL=…
sudo APP_DIR=/opt/secureleaf REPO_URL=https://github.com/<you>/SecureLeaf.git \
     /opt/secureleaf/infra/prod/scripts/setup-server.sh
```

It is **idempotent** (safe to run twice) and does: apt upgrade + `unattended-upgrades`; Docker Engine +
compose plugin; Docker log rotation (10 MB × 3); a 2 GB swap file; `fail2ban`; SSH hardening (keys
only, no root login, no passwords — it refuses to disable passwords if no key is installed); the host
firewall rules for 80/443; the app directory + clone; and the nightly backup timer. Log out and in
again so your user picks up the `docker` group.

## 4. Generate the environment file

```bash
cd /opt/secureleaf && infra/prod/scripts/generate-env.sh
```

It writes `infra/prod/.env` (mode 600, git-ignored). Strong random values are **generated** for
`DB_PASSWORD`, `JWT_SECRET`, `DRM_SIGNING_SECRET`, `MINIO_ROOT_USER`/`PASSWORD` and `REDIS_PASSWORD`; it
**prompts** for the external ones. Where to get them:

| Variable | Where it comes from |
|---|---|
| `SITE_HOST` | your DuckDNS name |
| `ADMIN_EMAILS` | comma-separated emails that become ADMIN (empty = nobody) |
| `SUPPORT_EMAIL` | shown in the UI |
| `GOOGLE_CLIENT_ID` | Google Cloud Console → Credentials → OAuth client (add `https://<SITE_HOST>` as an authorised JavaScript origin). Empty disables Google sign-in |
| `RAZORPAY_KEY_ID/SECRET/WEBHOOK_SECRET` | Razorpay test-mode dashboard (§0) |
| `MAIL_USER`, `MAIL_PASS` | Brevo → SMTP & API → SMTP (login and SMTP key). Host `smtp-relay.brevo.com`, port 587, STARTTLS |
| `MAIL_FROM` | **must be a sender you verified in Brevo** (Senders & IP), otherwise Brevo rejects every mail |
| `SENTRY_DSN`, `VITE_SENTRY_DSN` | optional; two Sentry projects (backend/frontend) or one. Empty = off |
| `VITE_LEGAL_REVIEWED` | leave empty until the legal text has been reviewed (Phase 18) |
| `OCI_S3_*` | see §7 (backups) |

The backend **refuses to start** in the `prod` profile if any secret is empty, shorter than 32
characters, or still a published dev default (`ProdSecretsConfig`).
Brevo's free tier is about 300 emails/day: a failed send is **logged and counted**
(`secureleaf.mail.send.failures`) but never breaks the purchase or upload that triggered it.

Re-running `generate-env.sh` refuses to overwrite an existing `.env` (a new `JWT_SECRET` signs everyone
out); `--force` overrides. `--non-interactive` reads the external values from environment variables.

## 5. Deploy

```bash
infra/prod/scripts/deploy.sh            # deploys origin/main;  deploy.sh v1.2.0  deploys a tag or SHA
```

It fetches and checks out the ref, **builds the images on the server** (5–10 minutes the first time —
Maven and npm run inside the build), tags them with the git SHA, runs `docker compose up -d`, then waits
until the backend is **ready** *and* `https://SITE_HOST/actuator/health` answers UP. **If that does not
happen it rolls back automatically** to the last version that passed the gate, and exits non-zero. It
then keeps only the newest 3 image tags. Flyway migrations run when the backend boots.

> A rollback restores the previous *code*; it cannot undo a database migration. Keep migrations
> backward-compatible for one release (expand → migrate → contract). See the runbook.

The first deploy also has Caddy request the certificate: `docker compose logs caddy` shows
`certificate obtained successfully`.

## 6. Post-deploy checks

1. `curl -s https://<SITE_HOST>/actuator/health` → `{"status":"UP",…}`; `curl -I https://<SITE_HOST>/actuator/metrics` must **not** return metrics.
2. Open the site: the app loads, the "Demo mode" banner shows, deep links (`/library`) work on refresh.
3. Register an account; receive (or check the Brevo log for) a password-reset email.
4. Run the golden path once by hand with disposable accounts: register → become creator → upload → LIVE →
   buy with a Razorpay **test** card → open the viewer and confirm the watermark shows.
   (The Playwright journey in `e2e/` uses the *mock* gateway, which production forbids, so it cannot
   run against this stack unmodified.)
5. Create the UptimeRobot monitor on `https://<SITE_HOST>/actuator/health` (public by design).
6. `infra/prod/scripts/smoke-watermark.sh` (on the server) → `WATERMARK SMOKE CHECK OK`.
7. Run one backup by hand (§7) and confirm the dump appears in Object Storage.
8. Confirm the startup log says `Payment gateway is RAZORPAY in TEST mode`.
9. `free -h` and `docker stats --no-stream`: total memory use should be roughly 2–5 GB — comfortably above
   Oracle's 20% "idle" threshold (ADR 0001), well below 12 GB.

## 7. Backups (off the server)

`backup.sh` runs nightly (02:30 UTC) from the `secureleaf-backup.timer` that `setup-server.sh`
installed (`systemctl list-timers | grep secureleaf`). It writes:

- `db/daily/secureleaf-YYYY-MM-DD.dump` — `pg_dump -Fc` (compressed), keeping the **last 7**
- `db/weekly/…` — Sundays' dump copied there, keeping the **last 4**
- `mirror/<bucket>/…` — an incremental `mc mirror` of the three MinIO buckets, kept current

**Set up Object Storage (S3-compatible):**
1. *Storage → Buckets → Create bucket* `secureleaf-backups` (Standard, private) in the same region.
2. Find your **namespace** (*Profile → Tenancy*). The endpoint is
   `https://<namespace>.compat.objectstorage.<region>.oraclecloud.com` → `OCI_S3_ENDPOINT`.
3. *Profile → My profile → Customer secret keys → Generate*: the access key id → `OCI_S3_ACCESS_KEY`,
   the secret (shown once) → `OCI_S3_SECRET_KEY`. Bucket name → `OCI_S3_BUCKET`.
4. Test: `infra/prod/scripts/backup.sh`.

**Free-limit maths (20 GB storage, 50,000 requests/month):** a nightly run costs a handful of requests
(1 PUT dump + listings + retention deletes) ≈ 10/night ≈ **300/month**. The mirror only uploads *new*
objects, so requests ≈ new objects: each new PDF is 1 raw object + 1 thumbnail + one tile per page.
A 100-page document ≈ 102 PUTs, so **≈ 15 such documents a day ≈ 46,000/month** would reach the request
limit; typical MVP volume (about 10 uploads a day of 20–30 pages ≈ 8,000/month) is far below it.
Storage: a tile is ~266 KB, so 20 GB holds ≈ 75,000 page-tiles (≈ 750 documents of 100 pages) plus the
small dumps (≈ 1–20 MB each × 11 kept). If you approach a limit, drop `secureleaf-tiles` from
`BACKUP_BUCKETS` (tiles can be regenerated from the raw PDFs by re-running processing) — this trades
restore convenience for far fewer requests and ~95% less storage.

**Copy outside Oracle** (the ADR's account-termination risk): from your laptop, weekly,
`infra/prod/scripts/download-backup.sh ubuntu@<server-ip> ~/secureleaf-backups` (scp of the newest dump).
Keep a copy of your `.env` in a password manager — without it a restored database is unreadable to a new server
(the `JWT_SECRET`/`DRM_SIGNING_SECRET` differences only sign users out, but Razorpay/Brevo credentials must be re-entered).

**Restore drill** (do this once now, and every few months): [`ops/runbook.md#restore-drill`](ops/runbook.md#restore-drill).
*A backup you have not restored is not a backup.*

## 8. Testing the production stack without a server

Everything except `setup-server.sh` (which needs a real Ubuntu VM) can be exercised locally or in CI,
on amd64, using Caddy's internal CA (`tls internal`) instead of Let's Encrypt:

```bash
export MINIO_IMAGE=… MC_IMAGE=…           # only if quay.io is unreachable; see infra/prod/.env.example
SITE_HOST=localhost HTTP_PORT=8080 HTTPS_PORT=8443 RAZORPAY_KEY_ID=rzp_test_x RAZORPAY_KEY_SECRET=x \
  RAZORPAY_WEBHOOK_SECRET=x infra/prod/scripts/generate-env.sh --non-interactive
export COMPOSE_FILE=$PWD/infra/prod/docker-compose.prod.yml:$PWD/infra/prod/docker-compose.test.yml
SKIP_GIT=1 DEPLOY_TAG=t1 HEALTH_CURL_ARGS=-k infra/prod/scripts/deploy.sh   # build + health-gated start
infra/prod/test/stack-test.sh             # health, SPA, /graphql, headers, ports, SSE through Caddy
infra/prod/test/backup-restore-test.sh    # backup → destroy volumes → restore → compare
infra/prod/test/generate-env-test.sh      # the generated .env is complete and strong
infra/prod/scripts/smoke-watermark.sh secureleaf/backend:t1   # fonts present, pixels change
shellcheck infra/prod/scripts/*.sh infra/prod/test/*.sh        # .shellcheckrc is in infra/prod/
```

To try the rollback: build a deliberately broken image (`FROM secureleaf/backend:t1` + `ENTRYPOINT ["sleep","3600"]`)
tagged `broken`, tag the caddy image `broken`, and run `DEPLOY_TAG=broken NO_BUILD=1 … deploy.sh` — the
previous version keeps serving and the script exits 1.

## 9. Environment variables (reference)

Every variable has a working default for local Docker Compose in `application.yml`; the `prod`
profile refuses dev defaults (`ProdSecretsConfig`, `DrmConfig`, `CommerceConfig`).

| Variable | Used by | Notes |
|---|---|---|
| `SPRING_PROFILES_ACTIVE=prod` | backend | set by the compose file |
| `DB_*`, `REDIS_*`, `MINIO_*` | backend | wired to the internal service names by the compose file |
| `JWT_SECRET`, `DRM_SIGNING_SECRET` | backend | ≥ 256 bits, generated |
| `PAYMENT_GATEWAY`, `RAZORPAY_*`, `PAYMENT_LIVE_ENABLED` | backend | test keys until Phase 18; see §0 |
| `GOOGLE_CLIENT_ID` | backend + frontend build (`VITE_GOOGLE_CLIENT_ID`) | empty disables Google sign-in |
| `ADMIN_EMAILS`, `SUPPORT_EMAIL` | backend | |
| `FRONTEND_BASE_URL`, `CORS_ALLOWED_ORIGINS` | backend | set to `https://SITE_HOST` by the compose file (same origin — CORS never triggers) |
| `MAIL_HOST/PORT/USER/PASS`, `MAIL_FROM`, `NOTIFICATION_EMAIL_ENABLED` | backend | Brevo, STARTTLS 587 |
| `SENTRY_DSN`, `SENTRY_ENVIRONMENT` | backend | inert when unset |
| `VITE_SENTRY_DSN`, `VITE_LEGAL_REVIEWED`, `VITE_GOOGLE_CLIENT_ID` | frontend **build** | baked into the JS at image build time |
| `PAYOUT_HOLD_DAYS`, `PAYOUT_MIN_PAISE` | backend | optional (7 days, ₹100) |
| `OCI_S3_ENDPOINT/BUCKET/ACCESS_KEY/SECRET_KEY`, `BACKUP_BUCKETS` | backup scripts | §7 |
| `MINIO_IMAGE`, `MC_IMAGE` | compose | default `quay.io/minio/{minio,mc}:latest` (ARM64 builds exist) |
| `HTTP_PORT`, `HTTPS_PORT` | compose | default 80/443; only Caddy publishes |

> **Rotating a secret:** see [`ops/runbook.md`](ops/runbook.md#rotate-a-secret).

---

## Appendix A — Plan B: managed free tiers

Kept only as an emergency demo path; **not** the production plan. [ADR 0001](adr/0001-hosting-oracle-always-free.md)
explains why it was rejected, using measured numbers. Its limits:

| Component | Free service | Measured limitation |
|---|---|---|
| Backend | Render free web service | 0.1 CPU / 512 MB (the JVM alone peaks ≈ 620 MB → OOM-killed); sleeps after 15 min idle (~1 min cold start); timers stop while asleep; SMTP ports blocked |
| Database | Supabase / Neon free | Free Postgres can expire (30 days on Render); pooled connections needed |
| Redis | Upstash | TLS required: `SPRING_DATA_REDIS_SSL_ENABLED=true`; request-count limits |
| Object storage | Supabase Storage (S3-compatible) | Set `MINIO_ENDPOINT/ACCESS_KEY/SECRET_KEY` |
| Frontend | Vercel (`frontend/vercel.json` ships the headers) | Different origin from the backend ⇒ set `VITE_GRAPHQL_URL`, `VITE_API_URL` (absolute URLs) **and** `CORS_ALLOWED_ORIGINS` on the backend |
| Email | Any HTTP-API provider (SMTP blocked on Render) | Would need an HTTP mail client — not implemented |

Expect: ~4.5 s per page view at 0.1 CPU, 15–50 minutes to process a 100-page upload. Use lower DPI and one
upload at a time. Same-origin (Caddy/nginx) deployments never need the CORS/absolute-URL variables; only this
split-origin shape does. Get them wrong in opposite ways and every request fails at the CORS preflight with
no useful error except in the browser console.

The local/CI frontend image (`infra/docker/frontend.Dockerfile`, nginx) is built with context `frontend/`
and is unrelated to production, which uses `infra/prod/caddy.Dockerfile`.
