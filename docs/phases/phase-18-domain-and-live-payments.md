# Phase 18 — Own domain + Razorpay live mode (MVP2)

> **Partly manual.** Its Issue has the labels `manual` + `mvp2`, so the scheduler ignores it.
> The code part is small; most of the work is the owner's accounts, KYC and legal review. Done together in a session.
> Can be done at **any point in MVP2**, and doesn't depend on Phases 10–17.

## Goal
Move from the test-mode public beta to a real business:
- a **custom domain**
- **Razorpay live payments** (after KYC)
- domain-authenticated email
- reviewed legal pages

## Owner prerequisites
1. **Buy a domain**, about ₹800–1,500 a year (for example `secureleaf.in`).
2. **Razorpay activation (KYC):** PAN, bank account, business type (an individual or sole proprietor is fine), and website details. Razorpay reviews the site, which needs the Terms, Privacy, Refund and Contact pages (built in 09C) with real business details.
3. **Legal review** of the 09C templates by a qualified person (GST/tax registration if needed, and what applies to a marketplace for digital goods). *Not something Claude can sign off.*

## Code and config changes
- **D1 — Domain.**
  - `SITE_HOST=www.<domain>`, with Caddy getting certificates automatically, and a redirect from the apex to `www` (or the other way).
  - The old DuckDNS name permanently redirects (301) to the new domain.
  - Update Google's Authorized origins, the Razorpay webhook URL and site URL, and `app.frontend-base-url`.
- **D2 — Email on the domain.** Authenticate the domain in Brevo, or move to Resend: SPF, DKIM and DMARC records, and `MAIL_FROM=no-reply@<domain>`. Deliverability test: mail should land in the inbox, not spam.
- **D3 — Live payments.**
  - Live keys (`rzp_live_…`) and a live webhook secret; `PAYMENT_LIVE_ENABLED=true` (the 09B guard); a new webhook in the **live** dashboard.
  - The demo banner disappears automatically (`paymentMode=LIVE`).
  - Run a **₹1 real purchase and refund** end to end before announcing.
- **D4 — Legal pages final.**
  - Fill in the legal name, address and support contact.
  - Set `VITE_LEGAL_REVIEWED=true` to remove the draft banner.
  - Record the effective date.
- **D5 — Google consent screen.** With a real domain you can verify ownership in Search Console and complete Google's brand verification, if the app qualifies.

## Acceptance criteria
- `https://www.<domain>` serves the app, and the old address redirects.
- Emails pass SPF, DKIM and DMARC.
- A live ₹1 purchase and refund works and shows in admin analytics.
- The legal pages have no draft banner.
- The 09B guard allowed live keys only because `PAYMENT_LIVE_ENABLED=true`.

## Learning note
Add a "Going from beta to business" section to the 09E note: DNS basics (A/AAAA/CNAME), email authentication (SPF, DKIM, DMARC), test vs live payment keys, and what KYC checks and why.
