# Phase 09C — Creator payouts, receipts & legal pages

> Spec for an unattended run. Decisions are numbered (D1…) and referenced from code comments.
> Requirements: post-MVP "creator payout" (manual version), PAY-08 receipts, plus the pages Razorpay requires before activating an account (used by Phase 18).
> Depends on: Phase 09B (refunds affect balances). Part of the **Launch track**.

## Context

- Buyers pay; the platform keeps 10%. But **creators can't get paid yet**.
- `creator_payouts` already exists (V1) with `amount_paise`, `status` (`REQUESTED`/`APPROVED`/`PROCESSING`/`PAID`/`REJECTED`), `gross_revenue_paise`, `platform_fee_paise`, `net_payout_paise`, `payout_method`, `payout_reference`, `requested_at`, `processed_at`, `notes` and `approved_by`. There's no code for it.
- `creator_profiles` stores `payout_email` and `payout_upi`.
- A public marketplace also needs **Terms, Privacy, Refund policy and Contact** pages. Razorpay checks for them before it activates live payments.
- **Bug to fix:** `index.css` has a **site-wide** `@media print { body { display: none } }` (from 05B, VIEW-07). It's meant for the reader only, but it also stops buyers printing receipts or legal pages.

For launch, payouts are **manual**: the admin transfers money by UPI or bank outside the app and records it here. RazorpayX automation is out of scope.

## Decisions

- **D1 — Balance.**
  - A creator's **available balance** = Σ `creator_earnings_paise` of COMPLETED, **non-refunded** order items **older than the hold period**, − Σ `amount_paise` of payouts not REJECTED.
  - `payouts.hold-days` defaults to **7**, matching the refund window (D6), so money that may still be refunded isn't paid out.
  - `pending` = earnings still inside the hold period.
  - Query `creatorBalance: CreatorBalance! { availablePaise pendingPaise lifetimeEarningsPaise paidOutPaise }`.
  - Computed with aggregate SQL. Nothing is stored denormalised.
- **D2 — Requesting a payout (creator).** `requestPayout(amountPaise: Long!): Payout!`:
  - minimum ₹100 (10000 paise, configurable), and no more than the available balance
  - **only one open request** (REQUESTED/APPROVED/PROCESSING) at a time
  - payout details are required (UPI id or email, already on the creator profile, with a form to edit them)
  - snapshot `gross`/`fee`/`net` onto the row
  - concurrency: take a row lock on the creator's `creator_profiles` row (`SELECT … FOR UPDATE`) before checking the balance, so two simultaneous requests can't both pass (same pattern as Phase 4 and Phase 7)
- **D3 — Admin workflow.**
  - `adminPayouts(status, page, size)`
  - `approvePayout(id)` REQUESTED→APPROVED
  - `markPayoutPaid(id, reference: String!)` APPROVED→PAID, with a transfer reference such as a UPI transaction id
  - `rejectPayout(id, reason: String!)` REQUESTED/APPROVED→REJECTED, which releases the balance

  Guarded state transitions throw `INVALID_STATE_TRANSITION`. Each action writes `admin_actions` (Phase 8) and notifies and emails the creator. Add a notification type via migration: add enum values `PAYOUT_REQUESTED` (to admins) and reuse `PAYOUT_STATUS_UPDATE` (creator). ⚠ Adding a Postgres enum value: use `ALTER TYPE … ADD VALUE` in its own migration and **don't** use the new value in the same transaction (the learning note explains why).
- **D4 — Statements.**
  - `creatorStatement(month: String!)` (format `YYYY-MM`) returns lines of sales, refunds, fees and payouts, with totals.
  - REST `GET /api/creator/statements/{yyyy-mm}.csv` (owner-only) downloads the same data as CSV.
  - The frontend shows a "Statements" tab on the creator dashboard.
- **D5 — Buyer receipts.**
  - Route `/orders/:id/receipt` (owner-only) shows a printable receipt: order id, date, product, creator, amount, payment id (masked), and test/live mode.
  - The purchase email links to it.
  - **Fix the print rule:** move it to `body.sl-reading { … }` and have `ReaderPage` add or remove the `sl-reading` class on mount and unmount. The reader still can't be printed (VIEW-07 regression test); receipts and legal pages can.
- **D6 — Legal and info pages.** Routes `/terms`, `/privacy`, `/refund-policy`, `/contact`, `/about`, linked from a new site footer on every page.
  - Content lives in `frontend/src/content/legal/*.ts` as structured sections, not free HTML strings (no `dangerouslySetInnerHTML`).
  - Each page shows **"Draft template — not legal advice. Review before enabling live payments."** until `VITE_LEGAL_REVIEWED=true`.
  - The **refund policy** matches D1 and 09B: digital goods; refunds on request within 7 days of purchase at the platform's discretion; access is revoked on refund.
  - The **privacy policy** must state exactly what we store: account data, purchase records, **viewer access logs (IP, user agent, pages viewed)**, the watermark containing the buyer's email, notifications, and processors (Razorpay, Brevo, Sentry, Oracle Cloud). It covers retention, and how to request deletion (email support). Mention India's DPDP Act 2023 in general terms.
  - **Contact** shows `platformInfo.supportEmail` (config `app.support-email`).
  - Business details (legal name, address) are placeholders filled in at Phase 18.
- **D7 — Creator terms.** The creator side of Terms covers: creators confirm they hold the rights to what they upload; the 10% commission; the payout hold period; and takedowns (Phase 8).
- **D8 — Money types.** Use `Long` or `paise` everywhere, with the GraphQL `Long` scalar. Never use floats for money.

## Acceptance criteria
1. Balance maths on a fixture with sales inside and outside the hold period, a refund and an existing payout: available, pending and lifetime figures are exact.
2. `requestPayout`:
   - rejected below the minimum, above the available balance, with an open request already, or without payout details
   - **two concurrent requests** (threads + latch) → exactly one succeeds
3. State machine: every legal transition works, and every illegal one throws `INVALID_STATE_TRANSITION`. Each admin action writes one audit row and one notification. Non-admins are denied.
4. A rejected payout's amount becomes available again, and a paid one never does.
5. The statement and the CSV totals match the fixture. The CSV is owner-only (another creator gets 403).
6. The receipt page is owner-only. Print CSS: `body.sl-reading` hides everything while the reader is mounted; with no reader, the receipt is printable (DOM/class test).
7. Legal pages are routed and linked from the footer, show the draft banner by default, and the refund policy text says "7 days".
8. Frontend tests cover: the balance card, the request-payout form validation, admin payout actions, the statements tab, the receipt page, and the footer links.
9. All existing tests stay green.

## Out of scope
- Automated payouts (RazorpayX / Route).
- TDS and GST invoicing.
- Final legal text (it's a reviewed template at Phase 18).
- Multi-currency.

## Learning note
Create `docs/learning-notes/phase-09c-payouts-legal.md`. Headline topics:
- ledger thinking (balance derived from events, never stored)
- hold periods and refund windows
- preventing double payouts with row locks
- state machines for money
- adding Postgres enum values safely
- why platforms need Terms, Privacy and Refund policies before payments go live
- scoping global CSS rules (the print bug)
