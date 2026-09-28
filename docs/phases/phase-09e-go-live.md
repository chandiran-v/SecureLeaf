# Phase 09E — Go live (owner + Claude, in a session)

> **Not for the unattended scheduler.** Its Issue has the label `manual` (not `phase`), so the scheduler ignores it.
> It needs the owner's accounts, logins and secrets, and is done together in a Claude Code session.
> Depends on: 09B, 09C and 09D merged. **At the end of this phase SecureLeaf is publicly live.**

## Goal
SecureLeaf runs at `https://<name>.duckdns.org` on an Oracle Always Free server, with every feature working. Payments use **Razorpay test mode**, with the demo banner visible. Backups run and have been tested, and monitoring is on.

## Before the session: accounts (owner, all free)
| # | Account | What to get |
|---|---|---|
| 1 | **Oracle Cloud** (home region **Mumbai** or **Hyderabad**; this is permanent) | Signed in. A card is needed for verification only, and Visa/Mastercard credit cards are the most reliable. |
| 2 | **DuckDNS** (sign in with GitHub or Google) | A subdomain, e.g. `secureleaf`, and its token |
| 3 | **Google Cloud** | An OAuth client of type *Web application*, with its client id |
| 4 | **Razorpay** (no KYC needed for test mode) | Test mode: Key Id `rzp_test_…`, Key Secret |
| 5 | **Brevo** | A verified sender email, plus SMTP login and SMTP key |
| 6 | **UptimeRobot** | Free account (monitoring every 5 minutes) |
| 7 | **Sentry** (optional) | Two DSNs: backend and frontend |

## In the session: steps (done together)
1. **Create the server:**
   - shape `VM.Standard.A1.Flex`, 2 OCPU, 12 GB, Ubuntu 24.04 aarch64, boot volume about 100 GB, your SSH public key
   - if you get "Out of host capacity", retry, or try another availability domain
   - add ingress rules for TCP 80 and 443 to the VCN security list
2. **Point DuckDNS** at the server's public IP.
3. SSH in, then run `setup-server.sh` → `generate-env.sh` → `deploy.sh`.
4. **Google:** add `https://<name>.duckdns.org` to *Authorized JavaScript origins*. Set the consent screen app name, support email, and links to `/privacy` and `/terms`, then publish it with basic scopes only.
5. **Razorpay (test mode):** create a webhook to `https://<name>.duckdns.org/api/webhooks/razorpay` with the secret from `.env`, for events `payment.authorized`, `payment.captured`, `payment.failed` and `refund.processed`. Turn on auto-capture.
6. **First admin:** register with an email listed in `ADMIN_EMAILS` (Phase 8 grants the role at startup and registration).
7. **Smoke tests on the live URL:**
   - run Phase 09's Playwright journey with `BASE_URL=https://<name>.duckdns.org`
   - by hand: Google sign-in, upload a PDF, test-mode purchase with `success@razorpay` UPI, read it (watermark, zoom, links), a review, a password-reset email arrives, the admin panel, a refund, a payout request
8. **Backups:** run `backup.sh` once by hand, restore it into a scratch stack, then run `download-backup.sh` to your laptop.
9. **Monitoring:** UptimeRobot on `/actuator/health`, and a Sentry test event if enabled.
10. **Go live:**
    - the demo banner is visible
    - the legal pages still show "draft" (fine for a test-mode beta)
    - fill in `docs/release-mvp1.md`'s final checklist, and tag `v1.0.0`
    - merge `feature/secure-leaf-mvp1` → `main`
    - announce

## Definition of live
- The public URL loads over HTTPS.
- Sign-up (email and Google) works.
- A buyer can make a test-mode purchase and read the book.
- Emails arrive.
- The nightly backup has run and a restore has been tested.
- An uptime alert reaches the owner.

## After going live
- Watch Sentry and UptimeRobot for a few days.
- Keep a list of real user feedback as `fix` Issues. The scheduler handles them before MVP2 work.
- Then switch the scheduler to MVP2 (`docs/phases/README.md`, "Switching to MVP2").

## Learning note
Create `docs/learning-notes/phase-09e-go-live.md`, written after the session: what happened, surprises and gotchas, and the release checklist as an interview story ("how I shipped my first production system").
