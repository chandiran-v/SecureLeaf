# Phase 09D — Production on one Oracle server

> **Status:** Done (code, scripts and local proof; the real server is Phase 09E, with the owner)
> **Built:** 2026-09-29
> **Requirement IDs covered:** the non-functional requirements (security, reliability, observability) applied to the chosen hosting — see [ADR 0001](../adr/0001-hosting-oracle-always-free.md). No new functional requirement IDs.
> **Commits:** see `git log --grep "Phase 09D"` on branch `auto/issue-30`

---

## 1. What we built, in plain English

Until now SecureLeaf only ran on a developer laptop. This phase builds everything needed to run it for
real on **one rented computer**: an Oracle Cloud "Always Free" ARM server with 2 CPU cores and 12 GB of
memory. A single command (`deploy.sh`) builds the app on that server, starts it, and checks that it
actually works — and if the new version does *not* work, it puts the old version back by itself.

The visitor talks to just one program, **Caddy**. Caddy fetches and renews the HTTPS certificate, hands out
the website (the built React app), and passes requests for data (`/graphql`, `/api/…`) to the Java backend.
Behind Caddy, on a private network nobody outside can reach, sit the database (Postgres), Redis and the file
store (MinIO). There are also scripts to prepare a brand-new server safely, to create the secret passwords,
and to back the data up every night to a *different* place and restore it again.

**Before this phase:** the app ran only locally; the "deployment" doc described a Render/Supabase/Vercel setup that the
ADR showed cannot even hold the backend's memory; the frontend Docker image could not be built; watermark fonts
were never checked in a container; nothing backed data up.
**After this phase:** `docker compose -f infra/prod/docker-compose.prod.yml` boots the whole stack; it was
booted, driven through HTTPS, backed up, destroyed and restored during this phase (on amd64, with Caddy's local CA).

---

## 2. Why it matters

- **A feature nobody can reach isn't shipped.** Everything before this phase is invisible until it has a hostname,
  a certificate and a process supervisor.
- **Hosting choices are engineering, not shopping.** The ADR measured the app (620 MB JVM peak, 0.45 CPU-seconds per
  watermarked page) and showed free managed tiers (512 MB, 0.1 CPU) cannot run it. The compose file's memory limits
  are that measurement turned into configuration.
- **One server is a single point of failure.** The ADR accepted that risk *only* if we can recover fast. So the
  recovery pieces (backups, restore, scripted rebuild, health-gated rollback) are part of the phase, not extras.
- **Failures that only appear in production.** A missing font in a slim container image makes watermarks fail
  *only there*. We test for exactly that.

---

## 3. New concepts introduced

### 3.1 Reverse proxy, TLS termination and same-origin serving

**What it is:** a reverse proxy is a front desk: every visitor talks to it, and it forwards each request to the right
internal service. "TLS termination" means the front desk handles the HTTPS encryption so the services behind it speak plain HTTP.

**The analogy:** a hotel reception. Guests never wander to the kitchen or the boiler room; they ask reception, which
phones the right department. Reception also checks ID (TLS) so the departments don't have to.

**Why we needed it here:** we have three kinds of things to serve (a static web app, a JSON/GraphQL API, a live event stream)
from one hostname and one certificate. Serving them from the **same origin** (same scheme + host + port) means the browser
never makes a cross-origin request, so **CORS** (the browser rule that blocks one site's JavaScript from reading another
origin's responses unless that origin opts in) never applies.

**How it works:**
1. Browser → `https://secureleaf.duckdns.org/…` → Caddy on port 443.
2. `/graphql`, `/api/*`, `/actuator/health` → `reverse_proxy backend:8080`; everything else → the files in `/srv`, falling back to `index.html` so React Router deep links work.
3. Caddy adds `X-Forwarded-Proto: https`; Spring trusts it (`server.forward-headers-strategy: framework`) so it knows the original request was secure.

**In our code:** `infra/prod/Caddyfile:34-49`
```caddy
@api {
	path /graphql /graphql/* /api/* /actuator/health /actuator/health/*
}
handle @api {
	request_body { max_size 60MB }
	reverse_proxy backend:8080 { flush_interval -1 … }
}
```
Only `/actuator/health` is proxied — `/actuator/metrics` never leaves the Docker network (verified by `stack-test.sh`).

**What breaks without it:** with the frontend and API on different origins you need CORS config on the backend *and* absolute
URLs in the frontend; get the two out of step and every request dies at the preflight with an error visible only in the browser console.

### 3.2 Automatic HTTPS (Let's Encrypt) with Caddy

**What it is:** Let's Encrypt is a free certificate authority. Caddy proves to it that you control the hostname (by answering a
challenge on port 80/443), receives a certificate, stores it in a volume, and renews it ~30 days before expiry — no cron job.

**The analogy:** a passport office that issues a free passport if you can show you live at the address, and Caddy is the assistant who
renews it before it lapses.

**Why here:** DuckDNS gives a free hostname, so a real certificate is possible at ₹0. For tests we use `tls internal` (Caddy's own
private CA) via `docker-compose.test.yml`, so the *same* Caddyfile is exercised on `localhost`.

**In our code:** `infra/prod/Caddyfile:12` (`import tls.d/*` — empty in production, one `tls internal` file in the test override).

**What breaks without it:** browsers refuse a non-HTTPS site with logins and payments; expired certs take the site offline at 3 a.m.

### 3.3 Container memory limits vs JVM heap (`MaxRAMPercentage`)

**What it is:** Docker's `mem_limit` is a hard ceiling: exceed it and the kernel kills the container (OOM-kill). The JVM's *heap* is only
part of its memory — metaspace, thread stacks, direct buffers and Java2D's native image buffers live outside it.

**The analogy:** a suitcase (the container limit) and the clothes folder inside it (the heap). If the folder alone fills the suitcase,
your shoes don't fit and the zip bursts.

**Why here:** the backend gets `mem_limit: 4g` and `-XX:MaxRAMPercentage=70`: the heap may use ≤ ~2.8 GB and ~1.2 GB stays for everything else. The JVM measured 620 MB at peak, so this is generous headroom, not a squeeze.

**In our code:** `infra/docker/backend.Dockerfile:43`, `infra/prod/docker-compose.prod.yml:77`
```dockerfile
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -Djava.awt.headless=true"
```
`ExitOnOutOfMemoryError` makes a wedged JVM *exit* so `restart: unless-stopped` replaces it, instead of limping on half-dead.

**What breaks without it:** a JVM that believes the whole 12 GB host is its own (old JVMs / no limit) grows until the kernel kills a random process, possibly Postgres.

### 3.4 Capacity planning from measured numbers

**What it is:** deciding the size of the machine and each container's budget from measurements, not guesses.

**In our code (budget, `docker-compose.prod.yml:1-15`):**

| Service | Limit | Measured basis |
|---|---|---|
| backend | 4 GB | 437 MB idle, 620 MB peak with 8 concurrent page views |
| postgres | 1.5 GB | `shared_buffers=384MB` (25% rule of thumb for a small box) |
| minio | 1 GB | 99 MB idle |
| redis | 384 MB | 256 MB `maxmemory` + overhead |
| caddy | 256 MB | tiny |
| **Total** | **≈ 7.1 GB** | leaves ~5 GB for the OS and page cache, plus 2 GB swap |

CPU: two cores, and a watermarked view costs ~0.45 CPU-s, so the processing pool is `core = max = 1` (`application-prod.yml`) — one upload at a time, readers keep a core.
Also a *floor*: Oracle reclaims servers whose CPU, network and memory are all under 20% for 7 days, so a comfortably-loaded 4–5 GB use keeps the VM.

**What breaks without it:** guessed limits either OOM-kill the service under load (too low) or waste the box (too high).

### 3.5 The two-firewall trap on Oracle

**What it is:** Oracle has *two independent* firewalls in front of your server. (1) The cloud network's **security list / NSG** (you edit it in the web console). (2) The **host firewall** inside Ubuntu: Oracle's images preload `iptables` rules that allow port 22 then `REJECT` everything.

**The analogy:** a building with a security gate on the street *and* a locked door in the lobby. Opening the gate does nothing if the lobby door still refuses visitors.

**Why here:** the classic first-time symptom is "I opened 443 in the console and the site still times out".

**In our code:** `infra/prod/scripts/setup-server.sh:152-168`
```bash
reject_line=$(iptables -L INPUT --line-numbers -n | awk '/REJECT/ {print $1; exit}')
iptables -I INPUT "$reject_line" -p tcp -m state --state NEW --dport "$port" -j ACCEPT
```
Rules are evaluated top to bottom, first match wins, so the ACCEPT must sit *above* the REJECT; `-C` first makes it idempotent; `netfilter-persistent save` survives reboots.

**What breaks without it:** the site is unreachable and neither firewall's UI shows an error.

### 3.6 Defense-in-depth server hardening

**What it is:** several independent layers, so one mistake isn't fatal.

**How (one row per layer):** only Caddy publishes ports (Postgres/Redis/MinIO unreachable even if a host rule is wrong) · host iptables allow only 22/80/443 · SSH keys only, no root login, no passwords (`setup-server.sh` refuses to disable passwords if no key is installed — it would lock you out) · `fail2ban` bans repeated SSH failures · `unattended-upgrades` applies security patches · containers run as a non-root user · secrets are generated, mode 600 and git-ignored, and the app refuses to boot with default/empty ones · security headers (HSTS, CSP, frame-ancestors none) from Caddy.

**What breaks without it:** a single misconfiguration (a published database port, a default password) becomes a full compromise.

### 3.7 Health-gated deploy with rollback

**What it is:** after starting the new version, *prove* it works before calling the deploy done; if it doesn't, restore the previous version automatically.

**The analogy:** a pilot's pre-landing checklist — you don't declare "landed" until the wheels are on the runway; if not, you go around.

**How it works** (`infra/prod/scripts/deploy.sh`): (1) note the last good tag → (2) build images tagged with the git SHA → (3) `compose up -d` → (4) poll until the backend container is `healthy` **and** `https://SITE_HOST/actuator/health` says UP → (5) on timeout, `IMAGE_TAG=<last good> compose up -d --no-build` and exit 1 → (6) prune to the last 3 tags.
The rollback target is stored in a state file only *after* a version passes the gate (`deploy.sh:49-51`, written at success), not "whatever is running now", because after a failed deploy what is running is the broken one.

**What breaks without it:** a bad deploy leaves the site down until a human notices. **What it does *not* do:** undo a database migration that already ran — keep migrations backward-compatible for one release.

### 3.8 Backups: 3-2-1, and restore drills

**What it is:** *3* copies of the data, on *2* kinds of storage, *1* off-site. And: **a backup you have not restored is not a backup.**

**Our layers:** live data on the server · nightly copy in Oracle Object Storage (S3-compatible) · a copy on the owner's laptop via `download-backup.sh` (protects against Oracle suspending the account — the ADR's named risk).
`backup.sh` = `pg_dump -Fc` (custom compressed format, restorable selectively with `pg_restore`) + `mc mirror` (incremental copy of the buckets) with retention 7 daily + 4 weekly. `restore.sh` reverses it onto a fresh stack.
`infra/prod/test/backup-restore-test.sh` *is* the drill: record row counts and an object checksum → back up → `compose down -v` (destroy everything) → restore → compare.

**What breaks without it:** the day the disk dies you discover the backup job has been silently failing for months (permissions, expired keys, an empty dump).

### 3.9 ARM64 images

**What it is:** the Oracle Ampere CPU is ARM (`aarch64`); laptops/CI are usually x86 (`amd64`). A container image is built for one CPU architecture (or is a "multi-arch" bundle).

**Why here:** every image must have a `linux/arm64` variant — `postgres:16`, `redis:7`, `caddy:2`, `eclipse-temurin`, `node`, `maven` and MinIO all do. We build images **on the server** so the architecture always matches; no registry, no cross-compiling.

**What breaks without it:** `exec format error` at container start.

### 3.10 Fonts in slim images (the production-only bug)

**What it is:** the watermark text is drawn by Java2D, which needs system fonts + fontconfig. Minimal container images often ship neither.

**In our code:** `infra/docker/backend.Dockerfile:24-28` installs `fontconfig fonts-dejavu-core`; `WatermarkSmokeCheck.java` renders a label on a blank PNG inside the built image and exits non-zero unless pixels changed (`infra/prod/scripts/smoke-watermark.sh`).

**What breaks without it:** every page view in production 500s ("Fontconfig head is null") or shows an *unwatermarked* page — while every developer laptop works. The second is a DRM failure, not a cosmetic one, which is why the check asserts *pixels changed*, not merely "no exception".

### 3.11 Error monitoring without leaking personal data

**What it is:** Sentry receives exceptions from the backend and browser. `SentryScrubber` (`backend/.../common/monitoring/SentryScrubber.java`) and `frontend/src/lib/monitoring.ts` strip emails, JWTs, `Bearer` tokens and `?sig=`/`?token=` query values *before* sending; user, cookies, headers and request bodies are dropped. Both are inert unless a DSN is set.

**Why:** a signed tile URL or a JWT in an exception message stored at a third party is a credential leak.

### 3.12 Notification failures must not break business flows (Brevo)

**What it is:** Brevo's free SMTP relay allows ~300 mails/day. Sending already happens `AFTER_COMMIT` on a background pool (Phase 4); this phase adds a counter (`secureleaf.mail.send.failures`) next to the existing log line (`NotificationDispatcher.java:69`), and turns off Spring's *mail health indicator* in prod so a Brevo outage cannot turn `/actuator/health` (the uptime probe and the deploy gate) DOWN.

---

## 4. Best practices applied

| Practice | What we did | Why it matters | Where |
|---|---|---|---|
| Least exposure | Only Caddy publishes ports; DB/Redis/MinIO on an internal network; MinIO console not published | A wrong host firewall rule can't expose a database | `docker-compose.prod.yml:50-53` |
| Resource limits on every container | `mem_limit` per service from measurements | One runaway service can't take the box down | `docker-compose.prod.yml:58,77,137,160,180` |
| Health checks + `depends_on: service_healthy` | Ordered, verified startup; `restart: unless-stopped` | Survives reboots and crashes without a human | `docker-compose.prod.yml` |
| Fail-fast config | Prod refuses empty / <32-char / default secrets | A misconfigured deploy fails in seconds, loudly | `ProdSecretsConfig.java:75-90` |
| Secrets generated, never typed | `openssl rand`, mode 600, git-ignored, refuses to overwrite | Humans pick bad passwords; overwriting logs everyone out | `generate-env.sh` |
| Idempotent provisioning | Every setup step checks state first | Re-running after a half failure is safe | `setup-server.sh` |
| Immutable, tagged artefacts | Images tagged with the git SHA, last 3 kept | Rollback is "run the old tag", not "rebuild" | `deploy.sh` |
| Log rotation | Docker `json-file` 10 MB × 3 on every service and the daemon | Disks fill quietly otherwise | compose `x-logging`, `setup-server.sh` |
| Scripts are linted and tested | `shellcheck` clean; each script has a real test | Shell fails silently | `infra/prod/test/*`, `.shellcheckrc` |
| Health probe reflects only what matters | Mail health indicator disabled in prod | Uptime alerts should mean "the site is down" | `application-prod.yml` |

---

## 5. What does what — file map

| File | Responsibility |
|---|---|
| `infra/prod/docker-compose.prod.yml` | The production stack: services, memory limits, health, one internal network |
| `infra/prod/docker-compose.test.yml` + `test-tls.caddy` | Test override: Caddy `tls internal` for `localhost` |
| `infra/prod/Caddyfile` / `caddy.Dockerfile` | Front door; built React app copied into `caddy:2` |
| `infra/prod/.env.example` | Every variable, what generates it and what asks for it |
| `infra/docker/backend.Dockerfile` | JRE image + fonts + healthcheck + JVM flags |
| `infra/docker/frontend.Dockerfile` | Fixed nginx image for local/CI (self-contained, context `frontend/`) |
| `infra/prod/scripts/setup-server.sh` | One-time idempotent server hardening + Docker + swap + firewall + backup timer |
| `infra/prod/scripts/generate-env.sh` | Creates `.env` with strong secrets |
| `infra/prod/scripts/deploy.sh` | Build, start, health-gate, rollback, prune |
| `infra/prod/scripts/backup.sh` / `restore.sh` / `download-backup.sh` | Off-server backup, rebuild, laptop copy |
| `infra/prod/scripts/smoke-watermark.sh` | Runs `WatermarkSmokeCheck` inside the image |
| `infra/prod/systemd/*` | Nightly backup timer |
| `infra/prod/test/*` | Stack, backup→restore and env-file tests |
| `backend/.../ProdSecretsConfig.java` | Refuses empty/short/default secrets in `prod` |
| `backend/.../SentryScrubber.java`, `frontend/src/lib/monitoring.ts` | Optional Sentry with scrubbing |
| `backend/.../application-prod.yml` | STARTTLS mail, pool = 1, mail health off |
| `backend/.../DocumentProcessingService.java` | Sets `started_at` if missing, logs duration |
| `docs/deployment.md`, `docs/ops/runbook.md`, `docs/ci/deploy.yml.example` | Owner's runbook, day-2 ops, deploy workflow |

**Request trace — a page view in production:**
1. Browser → `https://<host>/` → Caddy serves `/srv/index.html` with HSTS/CSP headers.
2. The app calls relative `/graphql` → Caddy `handle @api` → `backend:8080` (Caddy adds `X-Forwarded-Proto`).
3. Backend → Postgres (entitlement) + Redis (viewer session, single-use signature) + MinIO (tile) on the internal network.
4. Java2D draws the buyer's label using DejaVu fonts baked into the image; the PNG streams back through Caddy (uncompressed — PNG isn't in the `encode` match list).

**Deploy trace:** `deploy.sh v1.2` → `git checkout` → `compose build` (tag = SHA) → `up -d` → wait healthy → write `.last-good-tag` → prune.

---

## 6. Design decisions and trade-offs

### Decision: one server, docker-compose, no orchestrator
- **Alternatives considered:** managed free tiers (rejected by measurement), Kubernetes, several VMs.
- **Why:** the workload fits one box; compose is the same tool as local dev, so production looks like development.
- **What we gave up:** high availability. One VM, one disk, one region.
- **When we would revisit:** sustained CPU > ~60%, uptime needs beyond "restore in an hour", or the owner is ready to pay (ADR "Revisit when").

### Decision: Caddy instead of nginx (+ certbot)
- **Alternatives:** nginx with certbot cron; Traefik; a cloud load balancer.
- **Why:** automatic certificate issue *and* renewal with zero extra moving parts; a 60-line config; sane defaults.
- **Gave up:** nginx's ubiquity in interviews (the local image still uses nginx, so both are shown); fewer knobs.

### Decision: build images on the server, tag with the git SHA
- **Alternatives:** CI builds multi-arch images and pushes to a registry (GHCR); server only pulls.
- **Why:** no registry, no CI secrets, no cross-compilation; architecture always matches.
- **Gave up:** deploys take 5–10 minutes and use server CPU/RAM while building; no build cache shared with CI.
- **Revisit:** when deploys become frequent or the build competes with traffic.

### Decision: keep and fix `frontend.Dockerfile` (D5), rather than delete it
- **Why:** `.github/workflows/frontend-ci.yml` builds it with context `frontend/` and automation may not edit `.github/`. Deleting it would break CI on `main`. It's now self-contained (the nginx config is inlined via a BuildKit heredoc), so it builds with exactly that context; `infra/nginx/nginx.conf` was removed to keep one source of truth.
- **Gave up:** the nginx config can no longer be edited as a standalone file.

### Decision: rollback target = last version that *passed* the gate (state file)
- **Alternative:** ask Docker what's running now.
- **Why:** found while testing (§8): after a failed deploy the running version *is* the broken one, so a second attempt would "roll back" to the broken image.

### Decision: the mail health indicator is off in prod
- **Alternative:** leave the default (SMTP reachable = healthy).
- **Why:** Brevo blipping must not fail deploys or page you. (Also discovered while testing — §8.)
- **Gave up:** `/actuator/health` no longer shows SMTP problems; the failure counter and logs do.

### Decision: mirror all three buckets by default, with a documented escape hatch
- **Alternative:** back up only raw PDFs (tiles are regenerable).
- **Why:** restore then needs no reprocessing; the request maths (`deployment.md` §7) shows the free limit holds for realistic volumes.
- **Gave up:** request/storage headroom. **Revisit:** if `mc mirror` approaches 50k requests/month, drop tiles via `BACKUP_BUCKETS`.

---

## 7. Interview questions

### Beginner
**Q: What does a reverse proxy do, and why do we have one?**
A: It's the single public entrance. It terminates HTTPS, serves the static site and forwards API calls to the backend. That gives us one hostname and one certificate, hides Postgres/Redis/MinIO on a private network, and — because everything is the same origin — means no CORS.

**Q: What is a health check and what happens if it fails?**
A: A command Docker runs repeatedly to see if a service is really working, not just running. Other services wait for `healthy` before starting, and my deploy script won't call a deploy successful — and will roll back — if the backend never becomes healthy.

**Q: Why are secrets in a `.env` file that's git-ignored?**
A: Anything in git history is effectively public forever. The file is generated with random values, mode 600, and the app refuses to boot with empty or default ones.

### Intermediate
**Q: The JVM had `MaxRAMPercentage=70` in a 4 GB container. Why not 100%?**
A: The heap is only part of the JVM's memory: metaspace, thread stacks, direct buffers, and Java2D's native image buffers live outside it. If heap alone fills the container the kernel OOM-kills the process. 70% leaves ~1.2 GB for the rest, and I measured a 620 MB peak so there's plenty of room.

**Q: Explain how your deploy rolls back.**
A: It records the last tag that passed the health gate. A new deploy builds images tagged with the git SHA, starts them, and polls the container health plus the public `/actuator/health`. On timeout it re-runs `compose up` with the old tag, no rebuild, and exits non-zero. Caveat: it can't undo a migration that already ran, so migrations must be backward-compatible for one release.

**Q: Why publish only Caddy's ports?**
A: Defence in depth. Even if a host firewall rule is wrong, Postgres, Redis and MinIO have no published port, so they aren't reachable from the internet at all.

**Q: What's the 3-2-1 rule and how do you meet it?**
A: Three copies, two media, one off-site. Live data on the server, a nightly copy in Oracle Object Storage, and a dump pulled to my laptop. The laptop copy matters because the biggest risk in the ADR is Oracle suspending the account, which would take Object Storage with it.

### Advanced / follow-up probes
**Q: The site is down after you opened port 443 in the Oracle console. What's wrong?**
A: The two-firewall trap: Oracle's Ubuntu images have their own iptables rules that allow only 22 and then REJECT. I insert ACCEPT for 80/443 above that REJECT (order matters — first match wins) and persist with netfilter-persistent. I check `iptables -L INPUT --line-numbers`.

**Q: How would you prove a backup is good?**
A: Restore it. My test records row counts and a checksum of every object, backs up to a second MinIO standing in for Oracle, destroys all volumes, restores onto a fresh stack, and compares. It also checks retention: with `KEEP_DAILY=2` and three seeded old dumps, exactly two remain.

**Q: What are the weaknesses of this deployment?**
A: Single point of failure (one VM, one region), an hour of downtime in the worst case, up to 24 h of data loss between nightly backups, images built on the production box, the app uses MinIO's root credentials, and `setup-server.sh` can't be integration-tested in CI — I dry-run it and shellcheck it, but only a real VM proves it.

**Q: Why not just `docker compose down && up` to deploy?**
A: Downtime with no verification and no way back. Health-gating plus tagged images gives a verified start and a one-command retreat.

### "Tell me about a bug you fixed"
**Q: Tell me about a bug you found while testing your deployment.**
A: *Situation:* I was testing the rollback with a deliberately broken image. *What was wrong:* the script died at `docker compose up -d` before it ever reached the rollback code, because compose waits for the backend to be healthy (Caddy depends on it) and exits non-zero when it never is. Then, on a second attempt, it tried to "roll back" to the currently running version — which was the broken one. *Fix:* treat a failing `up` as "go check health" instead of aborting, and keep the rollback target in a state file written only after a version passes the gate. *Lesson:* test the failure path with a real failure; the happy path hid both bugs.

---

## 8. Gotchas and bugs we hit

| Symptom | Root cause | Fix | Lesson |
|---|---|---|---|
| `/actuator/health` was DOWN though the backend worked | Spring's mail health indicator tries the SMTP server (Brevo unreachable) | `management.health.mail.enabled: false` in `application-prod.yml` | A public/gating health check must include only what should take you offline |
| `deploy.sh` aborted before rolling back | `compose up -d` exits non-zero when a `depends_on: service_healthy` never becomes healthy; `set -e` killed the script | `\|\| log …` then run the health gate | `set -e` and "expected failure" don't mix |
| "Rolled back" to the broken version | rollback target was read from the running container | `.last-good-tag` written only after the gate passes | Ask "what was last *known good*", not "what's running" |
| Health check false negative on port 8443 | default health URL had no port; test stack listens on 8443 | include `HTTPS_PORT` when it isn't 443 | Probes must use the same URL users use |
| Caddy container `unhealthy` | `wget localhost:2019` resolved `::1` while the admin API listens on IPv4 | use `127.0.0.1` in the healthcheck and Caddyfile | `localhost` is two addresses |
| MinIO/mc images not pullable in CI sandbox | `quay.io` blocked; `minio/minio` doesn't exist on Docker Hub | `MINIO_IMAGE`/`MC_IMAGE` variables; tests use `chainguard/minio` (which bundles `mc`) | Make external registries configurable |
| `mc` container: "insufficient permissions" on the dump | dump is mode 600 owned by the host user; container ran as a different uid | `docker run --user "$(id -u):$(id -g)"` | Container uid vs host file ownership |
| Retention silently did nothing | the minimal `mc` image has no `awk`/`sort` | list in the container, process on the host | Don't assume coreutils inside minimal images |
| CI's `frontend-ci.yml` builds the frontend image with context `frontend/` | the pre-09D file copied a path outside that context (and the phase-9 "fix" changed the context CI didn't follow) | self-contained Dockerfile with an inlined nginx config | A Dockerfile is only correct relative to the context CI uses |
| `started_at` "never set" (spec) | it *is* set at claim by `ProcessingJobWorker`, but a direct `processAsync` call left it null | fallback in `processAsync` + duration log + assertion in `ProcessingPipelineIT` | Verify a bug report before "fixing" it |
| `sendDefaultPii` compile error in the browser SDK | option removed in `@sentry/react` v11 (PII off by default) | drop the option, scrub in `beforeSend` | Pin and read the SDK version you actually get |

---

## 9. New vocabulary

| Term | One-line meaning |
|---|---|
| Reverse proxy | A front server that forwards client requests to internal services |
| TLS termination | The proxy decrypts HTTPS so internal services can speak plain HTTP |
| Same-origin | Same scheme + host + port; browsers apply no CORS rules between them |
| ACME / Let's Encrypt | The protocol / free authority that issues certificates automatically |
| HSTS | Header telling browsers to use only HTTPS for this site |
| CSP | Header listing exactly which origins may load scripts/frames/connections |
| `mem_limit` | Docker's hard memory ceiling for a container (OOM-kill beyond it) |
| OOM-kill | The kernel killing a process that exceeded its memory |
| `MaxRAMPercentage` | JVM flag: max heap as a % of the container's memory limit |
| Swap | Disk space used as emergency memory |
| VCN security list | Oracle's cloud-level firewall (the "first" firewall) |
| iptables / `netfilter-persistent` | Linux host firewall / the service that reloads its rules at boot |
| fail2ban | Bans IPs that repeatedly fail to log in |
| `unattended-upgrades` | Ubuntu applying security updates automatically |
| Idempotent script | Safe to run twice; the second run changes nothing |
| Health-gated deploy | A deploy that isn't "done" until a health check passes |
| Rollback | Returning to the previous known-good version |
| 3-2-1 backups | 3 copies, 2 media, 1 off-site |
| `pg_dump -Fc` | Postgres logical backup in compressed custom format (restore with `pg_restore`) |
| `mc mirror` | MinIO client command that incrementally syncs a bucket |
| S3-compatible | Speaks Amazon S3's API — how we reach Oracle Object Storage |
| ARM64 / aarch64 | The CPU architecture of Oracle's Ampere servers; images must support it |
| Java2D / fontconfig | JDK drawing API / Linux font lookup — needed to draw watermark text |
| SSE | Server-Sent Events: one long HTTP response the server keeps writing to |
| `flush_interval -1` | Caddy setting: don't buffer, forward bytes immediately (needed for SSE) |
| Sentry | Hosted error-tracking service |
| `MeterRegistry` counter | Micrometer's named metric that only goes up (here: failed mails) |

---

## 10. If I had to defend this in a code review

- **Strongest:** every claim is exercised. The prod stack was booted, the SPA/GraphQL/headers/SSE checked *through the proxy*, a bad deploy rolled back with a non-zero exit, and a backup restored into a destroyed stack matched row for row and object for object. Limits are derived from the ADR's measurements, and the watermark-font failure has a test that asserts pixels changed.
- **Also strong:** secrets are generated, validated and refused when weak; the health probe is scoped to what should page a human.
- **Weakest — fix first:** a single VM and a nightly backup mean up to a day of lost data and up to an hour down. The cheap improvement is WAL archiving (or hourly `pg_dump`) to Object Storage. Next: the app runs with MinIO's *root* credentials (create a least-privilege service account per bucket), images are built on the box that serves traffic (move to CI-built multi-arch images in GHCR), and `setup-server.sh` — the most dangerous script — has only been dry-run and shellchecked, never run on a real Ubuntu VM in CI.
- **Not verified here:** Let's Encrypt issuance, real Brevo delivery, real Oracle Object Storage, ARM64 execution (built and run on amd64), and the Playwright journey against the prod stack (it requires the mock payment gateway that production forbids).
