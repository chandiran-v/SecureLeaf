# ADR 0001 — Host the public beta on one Oracle Cloud Always Free server

- **Status:** Accepted (2026-09-28)
- **Deciders:** project owner, with Claude
- **Supersedes:** the earlier plan in `CLAUDE.md` (Render + Supabase + Vercel + Upstash)

## Context
Goal: at the end of MVP1, SecureLeaf is **publicly live with every feature working, at ₹0/month**. Payments use Razorpay **test mode**; a custom domain and live payments come in MVP2 (Phase 18).

SecureLeaf's backend is heavy **by design**: the core DRM decision is to burn a watermark into every page **on the server, on every view**. It also processes PDFs into page images, runs timers (the upload queue, session cleanup, link backfill), and holds open SSE connections.

### Measured on the dev stack (2026-09-28, Intel i5-8250U, dev JIT)
| What | Measured |
|---|---|
| Backend JVM, idle | **437 MB** (heap used 180 MB of 330 MB committed; metaspace 133 MB) |
| Backend JVM, 8 page views at once | **556 MB**, **peaking at 619 MB** |
| Postgres / MinIO / Redis, idle | 59 / 99 / 13 MB |
| One watermarked page view | **~0.45 s** of one core; **266 KB** response |
| Processing an upload | **~1.2–3 s per page** (includes up to 5 s of queue wait) |
| **Minimum for the whole stack** | about **1 vCPU / 2 GB**; comfortable at **2 vCPU / 3–4 GB** |

## Options considered

### A. Managed free tiers (Render + Neon/Supabase + Upstash + B2 + Cloudflare Pages)
From Render's docs, checked on 2026-09-28:
- The free web service has **0.1 CPU and 512 MB**, and **spins down after 15 minutes** without traffic (about 1 minute to spin back up).
- SMTP ports 25/465/587 are **blocked**.
- Free Postgres **expires after 30 days**.
- Even the paid Starter plan ($7) is **512 MB**.

Against our measurements:
- The backend **alone** peaks above 512 MB, so it would be killed for running out of memory.
- A page view would take about 4.5 s at 0.1 CPU.
- A 100-page upload would take 15–50 minutes.
- Email is impossible over SMTP.
- Timers stop while the service sleeps.

→ **Rejected** for production. It's kept as an emergency "Plan B" demo (lower DPI, one upload at a time, email over an HTTP API).

### B. One Oracle Cloud Always Free server (chosen)
From Oracle's docs, checked on 2026-09-28:
- Ampere A1: 1,500 OCPU-hours and 9,000 GB-hours a month, **equivalent to 2 OCPU and 12 GB** for Always Free tenancies
- 200 GB block storage
- 20 GB object storage and 50,000 object requests a month
- idle servers are reclaimed if, over 7 days, CPU (95th percentile) **and** network **and** memory (A1 only) are all below 20%

The whole docker-compose stack fits with plenty of room, never sleeps, and SMTP works.

### C. A home PC + Cloudflare Tunnel
It's free and powerful, but only up while the PC is on. **Rejected.**

## Decision
Option **B**: Oracle Always Free ARM (2 OCPU / 12 GB, Ubuntu 24.04 aarch64) running docker-compose (Caddy + backend + Postgres + Redis + MinIO), with DuckDNS + Let's Encrypt for HTTPS, Brevo SMTP for email, Sentry (optional), UptimeRobot, and nightly backups to Oracle Object Storage **plus copies off Oracle**.

## Consequences
**Good**
- About **20× the CPU and 24× the RAM** of Render Free, at ₹0.
- All features at full quality (150 DPI tiles, timers, SSE, email).
- Production looks like local dev (the same compose services).
- **Staying above the idle threshold:** steady memory use (about 4–5 GB) is above 20% of 12 GB, so the server doesn't qualify as idle.

**Bad, with mitigations**
| Risk | Mitigation |
|---|---|
| Card needed at signup; some Indian cards rejected | Use a credit card, and retry |
| "Out of host capacity" when creating the server | Retry, or try another availability domain |
| Home region is permanent | Choose Mumbai or Hyderabad |
| Free accounts can be suspended; there's no support | Off-server backups (Oracle Object Storage **and** the owner's laptop), and a scripted rebuild on any server in about 1 hour (09D) |
| A single server is a single point of failure | `restart: unless-stopped`, health checks, a health-gated deploy with rollback, and a tested restore |
| We run the OS ourselves | Unattended upgrades, fail2ban, SSH keys only, scripted firewall (09D) |
| Only 2 cores | One upload processed at a time; MVP2 (tile cache, render pool, libvips) raises throughput |
| Free-tier terms can change again (they already halved from 4/24) | This ADR records the numbers; revisit if they change |

## Revisit when
- Oracle's free limits change again.
- Real usage consistently exceeds about 60% CPU.
- Uptime needs outgrow one server.
- The owner is ready to pay for hosting (then compare a small VPS at about ₹400–800/month).
