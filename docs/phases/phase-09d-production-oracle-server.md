# Phase 09D — Production package for one Oracle Always Free server

> Spec for an unattended run. Decisions are numbered (D1…) and referenced from code comments.
> Requirements: the NFRs (security, reliability, observability) applied to the chosen hosting; see [ADR 0001](../adr/0001-hosting-oracle-always-free.md).
> Depends on: Phase 09C. Part of the **Launch track**. Phase 09E then deploys it (with the owner).

## Context

We host **everything on one Oracle Cloud Always Free ARM server** (see the ADR):
- **2 OCPU and 12 GB RAM** (Oracle doc: 1,500 OCPU-hours and 9,000 GB-hours a month)
- 200 GB block storage, 20 GB object storage, 50,000 object requests a month
- Ubuntu 24.04 **aarch64**
- a free **DuckDNS** hostname with Let's Encrypt TLS
- **Brevo** SMTP for email
- optional **Sentry**

Measured on the dev stack: the backend JVM uses about 440 MB idle and **peaks at about 620 MB** under 8 simultaneous page views. A watermarked page view costs about 0.45 CPU-seconds and weighs about 266 KB. Processing takes about 1–3 s per page.

Phase 09 wrote `docs/deployment.md` for Render/Supabase/Vercel/Upstash. **This phase replaces that with the Oracle runbook**, keeping the managed stack only as a short "Plan B" appendix.

Known defects to fix here:
- `infra/docker/frontend.Dockerfile` copies `../nginx/nginx.conf`, which is outside the build context, so the image **cannot build**.
- `processing_jobs.started_at` is **never set**, so processing time can't be measured.

> **Automation constraint:** runs may not edit `.github/`. Any workflow goes in `docs/ci/*.example`.

## Decisions

- **D1 — One compose file for production:** `infra/prod/docker-compose.prod.yml`. Services:

  | Service | Image | Notes |
  |---|---|---|
  | `caddy` | built: Caddy + the **built React app** | The **only** service with published ports (80, 443). Automatic HTTPS for `${SITE_HOST}`. |
  | `backend` | built from `infra/docker/backend.Dockerfile` | profile `prod` |
  | `postgres` | `postgres:16` | volume `pgdata` |
  | `redis` | `redis:7` | `--appendonly yes --maxmemory 256mb --maxmemory-policy noeviction` |
  | `minio` + one-shot `minio-init` | `minio/minio`, `minio/mc` | creates buckets; console **not** published |

  - Every image must exist for **linux/arm64**; images are built **on the server**.
  - One internal network. Postgres, Redis and MinIO are never published.
  - `restart: unless-stopped`, a healthcheck on every service, and `depends_on: condition: service_healthy`.
- **D2 — Memory budget (12 GB box).** Compose `mem_limit`:

  | Service | Limit |
  |---|---|
  | backend | 4 GB (`-XX:MaxRAMPercentage=70`, `-XX:+UseG1GC`, `-XX:+ExitOnOutOfMemoryError`) |
  | postgres | 1.5 GB (`shared_buffers=384MB`) |
  | minio | 1 GB |
  | redis | 384 MB |
  | caddy | 256 MB |

  That's about 7 GB, leaving room for the OS and page cache, plus a **2 GB swap file**. Per the ADR, steady memory use stays above Oracle's 20% idle threshold.
- **D3 — CPU budget (2 cores).** In the `prod` profile, `processing.thread-pool` core = max = **1**, so one upload at a time and readers keep a core. The watermark stays on request threads (MVP2 Phase 12 moves it).
- **D4 — Same-origin serving through Caddy.** One hostname:
  - `/graphql`, `/api/*` and `/actuator/health` → backend. SSE must not be buffered (`flush_interval -1`). Request body limit 60 MB for uploads.
  - Everything else → the SPA, falling back to `index.html`.

  Consequences:
  - The frontend calls **relative URLs**, so there's **no CORS in production**.
  - Security headers for static files are set by Caddy (HSTS, frame-ancestors none, nosniff, referrer policy, and a CSP allowing Razorpay checkout per 09B, plus Google Identity Services).
  - Compression on for text; off for `image/png` tiles.
  - Build-time vars: `VITE_GOOGLE_CLIENT_ID`, `VITE_SENTRY_DSN`, `VITE_LEGAL_REVIEWED`.
- **D5 — Fix and replace the frontend image.** Delete the broken nginx-based `frontend.Dockerfile`, or fix it and keep it for local use (your choice; say which). Production uses `infra/prod/caddy.Dockerfile`: a node build stage copies `dist/` into `caddy:2`.
- **D6 — Backend image hardening.**
  - Make sure **fonts are installed** (`fontconfig` + `fonts-dejavu-core`). Java2D draws the watermark *text*, and slim images often have no fonts, which gives blank or failing watermarks only in production.
  - Keep the non-root user, and add a `HEALTHCHECK`.
  - A **smoke check** runs inside the built image: it renders a watermark on a sample PNG and asserts that pixels changed. Run it from the build or a script, and document the command.
- **D7 — Email via Brevo.** Spring Mail uses `MAIL_HOST=smtp-relay.brevo.com`, `MAIL_PORT=587`, STARTTLS, `MAIL_USER`, `MAIL_PASS`, and `MAIL_FROM`, which **must be a sender verified in Brevo**. Brevo's free tier is about 300 emails a day, so a send failure must never break the business flow (sending already happens after commit). Log the failure and count it with a metric.
- **D8 — Error monitoring (optional).**
  - Backend: `sentry-spring-boot-starter-jakarta`, active only when `SENTRY_DSN` is set, with `send-default-pii=false`.
  - Frontend: `@sentry/react`, active only when `VITE_SENTRY_DSN` is set.
  - Both scrub emails, tokens and signed URLs before sending.
  - When unset, zero behaviour change.
- **D9 — Server setup script:** `infra/prod/scripts/setup-server.sh`, idempotent, for Ubuntu 24.04 aarch64. It:
  - runs apt upgrade and enables `unattended-upgrades`
  - installs Docker Engine and the compose plugin
  - sets Docker log rotation (`max-size 10m`, `max-file 3`)
  - creates a 2 GB swap file
  - sets up `fail2ban`
  - hardens SSH: keys only, no root login, no passwords
  - **opens 80/443 in the host firewall.** Oracle's Ubuntu images ship with iptables rules that REJECT everything but 22, so insert ACCEPT rules *before* the REJECT and persist them (`netfilter-persistent`). The note explains the two-firewall trap (VCN security list **and** host iptables).
  - creates the app directory and clones the repo
- **D10 — Secrets.** `infra/prod/scripts/generate-env.sh` writes `infra/prod/.env` (mode 600, git-ignored) from `.env.example`, generating strong random values for `DB_PASSWORD`, `JWT_SECRET` (≥ 256 bits), `DRM_SIGNING_SECRET`, `MINIO_ROOT_USER`/`PASSWORD` and `REDIS_PASSWORD`. It prompts for the external ones: `SITE_HOST`, `ADMIN_EMAILS`, Google, Razorpay, Brevo, Sentry, `SUPPORT_EMAIL`. Phase 09's prod validator must reject any empty or default value.
- **D11 — Deploy script:** `infra/prod/scripts/deploy.sh [git-ref]`:
  1. `git fetch` and checkout of the ref
  2. build the images, tagged with the git SHA
  3. `docker compose up -d`
  4. wait for health (backend readiness + `https://SITE_HOST/actuator/health`)
  5. **on failure, roll back** to the previously running image tags and exit non-zero
  6. prune old images, keeping the last 3

  Flyway runs on boot (already the case).
- **D12 — Backups (off the server):** `infra/prod/scripts/backup.sh`, run nightly by a systemd timer that setup installs:
  - `pg_dump -Fc` (compressed), plus `mc mirror` of the MinIO buckets (**incremental**, few requests)
  - copied to **Oracle Object Storage** through its S3-compatible endpoint (customer secret keys)
  - retention: 7 daily + 4 weekly of the DB dump; bucket mirror kept current
  - stays inside the 20 GB / 50k-request free limits; the note shows the maths
  - `restore.sh` does the reverse onto a fresh stack
  - `download-backup.sh`, run from your laptop over SSH/scp, pulls the latest dump, because the account-termination risk in the ADR needs a copy **outside Oracle**
- **D13 — Operations basics.**
  - `/actuator/health` is public (for UptimeRobot); everything else under actuator stays internal.
  - Structured JSON logs (Phase 09) go to Docker logs with rotation.
  - Fix `processing_jobs.started_at`: set it when a job is claimed and log the processing duration.
  - Add `docs/ops/runbook.md`: logs, restart, disk full, rotate a secret, restore drill, renew or recover TLS, what to do if Oracle stops the VM, and rebuilding on a new VM in about an hour.
- **D14 — Docs.**
  - Rewrite `docs/deployment.md` as **the Oracle runbook**: create the VM (shape `VM.Standard.A1.Flex`, 2 OCPU / 12 GB, Ubuntu 24.04 aarch64, boot volume about 100 GB), open 80/443 in the VCN security list, DuckDNS, run setup, generate env, deploy, then the post-deploy checks. Plan B (managed free tiers) becomes a short appendix with its measured limitations.
  - `docs/ci/deploy.yml.example`: a GitHub Actions workflow that SSHes in and runs `deploy.sh` on a tag. The owner adds it.

## Acceptance criteria
1. `docker compose -f infra/prod/docker-compose.prod.yml config` is valid, and only `caddy` publishes ports.
2. **The production stack boots in the job** (amd64 is fine for CI). Use a local test override where Caddy uses `tls internal` and `SITE_HOST=localhost`. `https://localhost/actuator/health` returns UP, the SPA loads, and `/graphql` answers. Run Phase 09's Playwright journey against it if at all possible; otherwise state plainly in the PR what was not run and why.
3. The watermark smoke check passes **inside the built backend image** (fonts present).
4. The frontend image builds (the old context bug is fixed).
5. `backup.sh` → `restore.sh` round trip against the local prod stack: data and objects match after restoring into a fresh stack, using MinIO as a stand-in for Oracle Object Storage.
6. `deploy.sh` rollback: point it at a deliberately broken image, and the previous version keeps serving and the script exits non-zero.
7. `setup-server.sh` passes `shellcheck` and is idempotent (running it twice is safe); it can't be run for real in CI.
8. `generate-env.sh` produces a complete `.env` with strong values, and the prod validator accepts it and rejects a blank or default one.
9. `processing_jobs.started_at` is set (integration test).
10. SSE works through Caddy: a notification reaches an open stream through the proxy (integration or scripted test against the local prod stack).
11. All existing tests stay green.

## Out of scope
- Your own domain, and Razorpay live mode (Phase 18).
- Kubernetes, multiple servers.
- A CDN.
- Paid monitoring.

## Learning note
Create `docs/learning-notes/phase-09d-production-single-server.md`. Headline topics:
- capacity planning from **measured** numbers (the ADR table)
- the single-server trade-off (simplicity vs. single point of failure) and how backups plus scripted rebuilds reduce it
- a reverse proxy, TLS and same-origin serving (why no CORS)
- container memory limits vs. JVM heap (`MaxRAMPercentage`)
- the two-firewall trap on Oracle
- defense-in-depth server hardening
- deploy with health-gated rollback
- the 3-2-1 backup idea and restore drills (a backup you haven't restored isn't a backup)
- ARM64 images
- the fonts-in-slim-images production bug
