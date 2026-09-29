# Phase 09C — Creator payouts, receipts & legal pages

> **Status:** Done
> **Built:** 2026-09-29
> **Requirement IDs covered:** post-MVP "creator payout" (manual version); PAY-08 (buyer receipts — the purchase email now links to a printable receipt); VIEW-07 (the print block, re-scoped so it only covers the reader). Prepares the Terms/Privacy/Refund/Contact pages Razorpay requires before live activation (Phase 18).
> **Spec:** [`docs/phases/phase-09c-payouts-receipts-legal.md`](../phases/phase-09c-payouts-receipts-legal.md) — decisions D1–D8 are referenced throughout.
> **Commits:** see `git log --grep "Phase 09C"` on the branch `auto/issue-29`.

---

## 1. What we built, in plain English

Until now buyers could pay us, and we kept 10%, but **a creator could never actually get their 90%**. This phase closes the money loop. A creator opens a new **Payouts** tab on their dashboard and sees four numbers: what they can withdraw now, what is still "pending", what has already been paid to them, and their lifetime earnings. They type an amount, press *Request payout*, and an admin sees it in a new **Payouts** queue. The admin sends the money by UPI or bank transfer *outside the app*, then comes back and presses **Approve → Mark paid** (typing the UPI transaction id), or **Reject** with a reason. Each step emails and notifies the creator and leaves a row in the admin audit log.

Two small things sit next to that. Creators get a **Statements** tab (a month's sales, refunds, fees and payouts, with a CSV download for their accountant). Buyers get a **printable receipt** page for each purchase, linked from the purchase email.

Finally, a public marketplace needs **Terms, Privacy, Refund policy, Contact and About** pages before Razorpay will switch on real payments. They are linked from a new footer on every page, and they carry a loud "Draft template — not legal advice" banner until the owner sets `VITE_LEGAL_REVIEWED=true`.

There was also a **bug** to fix on the way: a CSS rule meant to stop the secure reader being printed had been written for the *whole site*, so it also blanked the printed receipt. It is now scoped to the reader only.

**Before this phase:** creators had earnings on paper but no way to be paid; no receipts; no legal pages; nothing on the site could be printed.
**After this phase:** manual, auditable payouts; statements + CSV; receipts that print; legal pages; the reader is still unprintable.

---

## 2. Why it matters

- **A marketplace that can't pay sellers isn't one.** Creators are the supply side; without payouts the demo has no ending.
- **Money code must be provable.** Every number here is derived from facts that already exist (orders, refunds, payout rows), and every state change is guarded and audited. That is the difference between "we think the balance is right" and "we can show why".
- **The hold period protects us from a nasty race with refunds.** Refunds are allowed for 7 days (Phase 09B). If we paid creators the instant a sale happened, a refund a day later would mean paying out money we no longer have.
- **Payment providers require it.** Razorpay reviews the site for Terms, Privacy, Refund and Contact pages before activating live payments (Phase 18). Building them now means that step isn't blocked.
- **Skip it and:** creators can't be paid (so the business model doesn't work), a double payout is one concurrent click away, and the legal/print gaps block go-live.

---

## 3. New concepts introduced

### 3.1 Ledger thinking — a balance is derived from events, never stored

**What it is:** Instead of keeping a `balance` number that every sale, refund and payout has to remember to update, we keep only the *events* (each sale's earnings, each payout row) and compute the balance with a query every time it is read.

**The analogy:** Your bank statement is a list of transactions; the balance at the bottom is just their sum. A bank never "sets" your balance directly — it adds a transaction. If the two ever disagreed, the transactions win.

**Why we needed it here:** money touches this number from five places (sale, refund, payout request, reject, paid). A stored column would need all five to update it correctly, forever, including in the error paths.

**How it works:**
1. `lifetime` = Σ `creator_earnings_paise` of COMPLETED (non-refunded) order items — one aggregate query.
2. `cleared` = the same sum, but only sales that completed at least `payouts.hold-days` (7) ago.
3. `committed` = Σ `amount_paise` of payouts that are **not** REJECTED (requested, approved and paid all "spend" the balance).
4. `available = cleared − committed`, `pending = lifetime − cleared`, `paidOut` = Σ of PAID payouts.

**In our code:** `backend/src/main/java/com/secureleaf/creator/service/PayoutService.java:60`
```java
long cleared = orderItemRepository.sumEarningsCompletedUpTo(creatorId, OrderStatus.COMPLETED, cutoff);
long committed = payoutRepository.sumAmountExcludingStatus(creatorId, PayoutStatus.REJECTED);
...
long available = Math.max(0, cleared - committed);
```

**What breaks without it:** a stored balance drifts. A refund handler that forgets to subtract, a rejected payout that forgets to add back, a crash between two updates — and now the number is wrong with no way to tell which of the facts is to blame. With derived balances, a rejected payout "returns" the money automatically: it simply stops being counted.

### 3.2 Hold periods and the refund window

**What it is:** Earnings from a sale are "pending" for 7 days before they can be withdrawn. The 7 days is the same as the refund window in the Refund Policy.

**The analogy:** A shop that pays its sales staff commission only after the returns period ends.

**Why we needed it here:** a refund reverses the creator's earnings. If the money was already paid out, we'd be out of pocket and would have to claw it back from a creator — awkward, sometimes impossible.

**How it works:** the clock starts at `orders.completed_at` (added in migration V10, set once by `Order.transitionTo(COMPLETED)`), not at `created_at` — an order can sit PENDING for hours before it is paid — and not at `updated_at`, which any later write moves. Refunded orders are excluded entirely because only COMPLETED orders are summed.

**In our code:** `backend/src/main/java/com/secureleaf/commerce/entity/Order.java:66`, `backend/src/main/resources/db/migration/V10__creator_payouts.sql`

**What breaks without it:** payouts run ahead of refunds. There is one edge we could not remove: a refund *after* a payout can push `cleared` below `committed`. We show "available = 0" (never negative) and recovering the money is a manual matter — see §10.

### 3.3 Preventing double payouts with a row lock (check-then-act)

**What it is:** "Is there an open request? Is the amount ≤ the balance? OK, insert." is a *check-then-act* sequence. Two requests running at the same time can both do the check before either does the insert, and both pass.

**The analogy:** two people at two tills both see "1 ticket left" and both sell it.

**Why we needed it here:** it is the classic way to pay a creator twice. This is the same problem as Phase 4's per-buyer lock and Phase 7's review counters.

**How it works:**
1. `INSERT … ON CONFLICT DO NOTHING` makes sure the creator's `creator_profiles` row exists (`becomeCreator` normally creates it, but a creator role granted any other way — test fixtures, a manual database change — has none).
2. `SELECT … FOR UPDATE` on that row (`findByIdForUpdate`) — the second request blocks here until the first commits.
3. Now check details, minimum, open request, balance — the second request *sees* the first one's row and is refused.
4. A partial unique index `uq_creator_payouts_one_open` (one open request per creator) is the safety net if some future code path forgets the lock.

**In our code:** `PayoutService.java:77-104`; `V10__creator_payouts.sql:18`; test `PayoutIT.java:168` (`twoConcurrentRequestsExactlyOneSucceeds` — two threads released together by a `CountDownLatch`, each asking for an amount that fits alone but not together; exactly one wins).

**What breaks without it:** two payouts for the same money. The lock is on the *profile* row, not the whole table, so different creators never wait for each other.

### 3.4 State machines for money

**What it is:** a payout can only move along fixed arrows: `REQUESTED → APPROVED → PAID`, and `REQUESTED|APPROVED → REJECTED`. `PAID` and `REJECTED` are terminal. Anything else throws `INVALID_STATE_TRANSITION`.

**Why we needed it here:** "mark paid" on an already-rejected payout, or "reject" on one that has been paid, would quietly corrupt the balance. Writing the allowed transitions in one place (`PayoutStatus.canTransitionTo`) means an illegal jump fails loudly instead of wherever someone called `setStatus`. Same idea as `OrderStatus` (Phase 4).

**How it works:** every admin action (1) locks the payout row with `FOR UPDATE` so two admins can't act on it at once, (2) checks `canTransitionTo`, (3) changes state, (4) writes one `admin_actions` row, (5) writes one notification — all in one transaction, so all five happen or none do. `PROCESSING` exists in the database enum from V1 (reserved for automated payouts, out of scope) so nothing moves into or out of it.

**In our code:** `PayoutStatus.java:19`, `AdminPayoutService.java:100`; test `PayoutIT.everyIllegalTransitionIsRefused`, which also asserts the *refused* attempts wrote no audit rows.

**What breaks without it:** the illegal transition that pays a rejected request.

### 3.5 Adding a Postgres enum value safely

**What it is:** `notification_type` is a Postgres `ENUM`. We needed a new value, `PAYOUT_REQUESTED`.

**Why it is tricky:** Flyway runs each migration inside a transaction, and Postgres refuses to *use* a freshly added enum value in the same transaction that added it ("unsafe use of new value"). So `ALTER TYPE … ADD VALUE` goes in its **own** migration (`V11`) and nothing in that file uses it. Code that uses the value runs later, in a different transaction.

**In our code:** `backend/src/main/resources/db/migration/V11__notification_payout_requested.sql`

**What breaks without it:** if you add the value and insert a row using it in one migration, the migration fails halfway in production. Also: enum values can be added but not removed — a reason some teams prefer `VARCHAR + CHECK` for values that change often (see §6).

### 3.6 Scoping global CSS rules (the print bug)

**What it is:** Phase 05B added `@media print { body { display: none } }` so the secure reader can't be printed. But it was written against the bare `body` element, so it applied to *every page*.

**The analogy:** a "no photography" sign meant for one gallery room, bolted to the front door of the museum.

**How it works:** the rule is now `body.sl-reading { display: none }`. `ReaderPage` calls the `useReadingMode()` hook, which adds the class `sl-reading` to `<body>` when the reader mounts and removes it in the `useEffect` cleanup when it unmounts. Chrome that has no place on paper (header, footer, demo banner, buttons) gets a `.no-print` class instead.

**In our code:** `frontend/src/index.css:61`, `frontend/src/hooks/useReadingMode.ts`

**Tests:** the CSS text is asserted to contain `body.sl-reading` and *no* bare `body {` inside `@media print`; a hook test proves the class is present only while mounted; a `ReaderPage` test proves the real page sets and clears it (VIEW-07 regression).

**What breaks without it:** buyers can't print receipts, and the legal pages print as a blank sheet — a bug that only shows up when someone presses Ctrl+P, so nothing catches it.

### 3.7 Why marketplaces need Terms, Privacy and Refund pages before payments go live

**What it is:** Payment processors (Razorpay) and the law both expect a public marketplace to say, in writing, what buyers and sellers agree to, what personal data is stored and who sees it, and when money is returned.

**Why we needed it here:** Razorpay checks for these pages during account activation (Phase 18). More importantly, our data practices are unusual — we log IP, user agent and pages viewed for every reading session, and stamp every page with the buyer's email — and India's DPDP Act 2023 expects that to be disclosed.

**How it works:** the text lives in `frontend/src/content/legal/*.ts` as **structured data** (`{heading, paragraphs[], bullets[]}`) rendered by one `LegalPage` component as plain React text nodes. There is no `dangerouslySetInnerHTML`, so there is no HTML injection surface, and a lawyer can edit wording without touching JSX. The refund policy's "7 days" comes from one constant, `REFUND_WINDOW_DAYS`, used by the Terms and the Refund Policy so those two can't drift apart (the backend's `payouts.hold-days` is a separate config value that must be kept at 7 by hand — see §10).

**What breaks without it:** Razorpay refuses to activate you, and you'd be collecting undisclosed personal data.

### 3.8 Money as integers, all the way to the keyboard (D8)

**What it is:** every amount is an integer number of **paise** — in Java `long`, in Postgres `BIGINT`, in GraphQL the `Long` scalar. The one place a *human* types a decimal is the request form, and there we convert with string maths (`"0.29"` → `29`) instead of `parseFloat(x) * 100`.

**Why:** in floating point, `0.29 * 100` is `28.999999999999996`. A payout of "₹0.29" would be 28 paise after truncation. `parseRupeesToPaise` uses a regex and integer arithmetic; its test includes exactly that case.

**In our code:** `frontend/src/lib/payoutAmount.ts:9`

### 3.9 CSV export and CSV injection

**What it is:** the statement CSV contains product titles, which creators control. A cell that starts with `=`, `+`, `-` or `@` is executed as a **formula** when opened in Excel or Google Sheets — an attacker names a product `=HYPERLINK(...)` and it runs on the victim's machine.

**How we defend:** `csvCell` quotes every text cell per RFC 4180 (doubling embedded quotes) and prefixes a leading apostrophe to anything starting with a formula character, which spreadsheets treat as "this is text". Amounts stay integer paise, so there is no rounding in the file.

**In our code:** `backend/src/main/java/com/secureleaf/creator/service/StatementService.java:117`; unit test `StatementServiceTest`.

---

## 4. Best practices applied

| Practice | What we did | Why it matters | Where |
|---|---|---|---|
| Derived, not stored, balances | Aggregate SQL over existing events (D1) | Can't drift out of sync with the facts | `PayoutService.java:60` |
| Lock before check-then-act | `FOR UPDATE` on the creator profile row before validating (D2) | No double payout | `PayoutService.java:77` |
| Defence in depth | Partial unique index for "one open request" as well as the code check | Survives a future code path that forgets the lock | `V10__creator_payouts.sql:18` |
| Guarded state machine | `canTransitionTo` + row lock + audit + notify in one transaction (D3) | Illegal jumps fail loudly; audit and change commit together | `AdminPayoutService.java:100` |
| Snapshot at request time | Destination (UPI/email) copied onto the payout row | Editing your profile can't redirect a request an admin already reviewed | `V10`, `PayoutService.java:114` |
| Single source of truth for a number | `REFUND_WINDOW_DAYS`, `payouts.hold-days` | Terms, refund policy and code can't disagree | `content/legal/types.ts` |
| Least privilege on REST | `/api/creator/**` limited to CREATOR in `SecurityConfig`; creator id read from the JWT, never the URL | "Owner-only" CSV with nothing to tamper with | `SecurityConfig.java`, `CreatorStatementController.java` |
| BOLA → 404 | Receipt query filters by buyer id; someone else's order is "not found" | Doesn't confirm an order exists | `ReceiptService.java` |
| Data minimisation | Payment id is masked (`pay_••••9876`) | A printed/shared receipt can't leak the full id | `ReceiptService.java:50` |
| Scoped CSS | Print block behind `body.sl-reading`, class managed by a hook with cleanup | One rule, one purpose | `index.css:61` |
| No HTML injection | Legal text as data, rendered as text nodes | No `dangerouslySetInnerHTML` | `LegalPage.tsx` |
| Own migration for enum values | `V11` only adds the value | Postgres "unsafe use of new value" | `V11__…sql` |

---

## 5. What does what — file map

| File | Responsibility |
|---|---|
| `db/migration/V10__creator_payouts.sql` | `orders.completed_at`, `creator_payouts.payout_destination`, one-open-request unique index |
| `db/migration/V11__notification_payout_requested.sql` | New `PAYOUT_REQUESTED` enum value (own migration) |
| `creator/service/PayoutService.java` | Balance, `requestPayout` (locked), payout details, history |
| `creator/service/AdminPayoutService.java` | Approve / mark paid / reject; audit + notify |
| `creator/entity/PayoutStatus.java` | The state machine (`canTransitionTo`, `isOpen`) |
| `creator/service/StatementService.java` | Monthly statement (IST months), CSV, injection defence |
| `creator/controller/CreatorStatementController.java` | `GET /api/creator/statements/{yyyy-mm}.csv` |
| `creator/resolver/PayoutResolver.java`, `admin/resolver/AdminPayoutResolver.java` | GraphQL entry points (`@PreAuthorize`) |
| `commerce/service/ReceiptService.java` | Owner-only receipt, masked payment id |
| `notification/service/NotificationService.java` | `notifyPayoutRequested`, `notifyPayoutStatus`, receipt link in the purchase email |
| `graphql/schema.graphqls`, `admin.graphqls` | New types, queries and mutations |
| `frontend/src/components/payouts/*` | Balance card, request form, details form, history, statements tab |
| `frontend/src/pages/admin/AdminPayoutsPage.tsx` | The admin queue |
| `frontend/src/pages/buyer/ReceiptPage.tsx` | Printable receipt |
| `frontend/src/pages/legal/LegalPages.tsx`, `components/legal/LegalPage.tsx`, `content/legal/*.ts` | Legal pages as structured data |
| `frontend/src/components/layout/SiteFooter.tsx` | Footer links on every page |
| `frontend/src/hooks/useReadingMode.ts`, `index.css` | The scoped print rule |
| `frontend/src/lib/payoutAmount.ts` | Rupees → paise without floats; form validation rules |

**Request trace — `requestPayout(amountPaise: 50000)`:**
1. `PayoutResolver.requestPayout` (`@PreAuthorize("hasRole('CREATOR')")`) takes the creator id from the JWT →
2. `PayoutService.requestPayout` opens a transaction, ensures the profile row exists, then `SELECT … FOR UPDATE` on it →
3. checks payout details, minimum ₹100, no open request, amount ≤ `available` (computed by aggregate SQL) →
4. inserts the payout row with the gross/fee snapshot and the UPI destination →
5. `NotificationService.notifyPayoutRequested` writes one notification per active admin (AFTER_COMMIT: push + email) →
6. commit releases the lock; the next waiting request now sees an open request and is refused with `PAYOUT_ALREADY_OPEN`.

---

## 6. Design decisions and trade-offs

### Decision: derive the balance on every read
- **Alternatives considered:** a `balance_paise` column on `creator_profiles`, updated by every sale/refund/payout; a nightly-materialised summary table.
- **Why we chose this:** correctness by construction, and one indexed aggregate per creator is cheap at our scale.
- **What we gave up:** each dashboard load runs 3 aggregate queries. A creator with millions of sales would want a periodically-materialised total plus a delta.
- **When we would revisit:** if the balance query shows up in slow-query logs.

### Decision: hold measured from a new `orders.completed_at` column
- **Alternatives considered:** `orders.created_at` (wrong: a PENDING order can complete hours later); `orders.updated_at` (any later write moves it); the entitlement's `purchased_at` (revoked on refund).
- **Why:** an explicit, set-once column says exactly what it means. Backfilled from `updated_at` for existing rows.
- **What we gave up:** a schema change and one line in `Order.transitionTo`.

### Decision: manual payouts, recorded in-app
- **Alternatives considered:** RazorpayX / Route automated payouts.
- **Why:** they need business KYC and a funded account — Phase 18 territory — and the workflow (request → approve → paid) is exactly what an automated integration would later hang off.
- **What we gave up:** an admin has to send each transfer by hand. There is no way for the app to *verify* the transfer happened; "paid" is the admin's word plus a reference id.
- **When we would revisit:** when volume makes the manual step painful.

### Decision: one open request at a time
- **Alternatives considered:** allow several concurrent requests, each within the remaining balance.
- **Why:** simpler for the admin and the creator, and simpler to reason about; the partial unique index enforces it in the database.
- **What we gave up:** a creator can't queue a second request.

### Decision: statement months use Asia/Kolkata
- **Alternatives considered:** UTC months.
- **Why:** a sale at 23:30 IST on 31 January is "January" to the creator and their accountant, though it is already 1 February in UTC.
- **What we gave up:** creators outside India see IST boundaries. Fine for an INR-only product.

### Decision: `ALTER TYPE … ADD VALUE` (enum) rather than switching to `VARCHAR + CHECK`
- **Why:** consistent with the existing schema (every status is a Postgres enum). Adding a value is cheap.
- **What we gave up:** enum values can't be removed, and the own-migration rule is a trap for the unwary.

### Decision: legal text as structured data, not HTML
- **Alternatives considered:** Markdown rendered to HTML; HTML strings with `dangerouslySetInnerHTML`.
- **Why:** no injection surface, no extra dependency, easy for a lawyer to edit and diff.
- **What we gave up:** no rich formatting beyond headings, paragraphs and bullet lists; no links inside paragraphs.

---

## 7. Interview questions

### Beginner
**Q: Why not just keep a `balance` column for each creator?**
A: Because then every path that touches money — a sale, a refund, a payout request, a rejection — has to remember to update it, and if any path forgets, or crashes halfway, the number is silently wrong. We compute the balance from the sales and payout rows each time, so it can't disagree with them. It's how a bank statement works: the balance is the sum of the transactions.

**Q: What is the hold period and why 7 days?**
A: Earnings from a sale can't be withdrawn for 7 days. Buyers can ask for a refund for 7 days, and a refund reverses the creator's earnings; if we'd already paid them, we'd have to claw the money back. Matching the two numbers means money that might still be refunded is never paid out.

**Q: What is a state machine and where do you use one here?**
A: A fixed set of states and the only allowed moves between them. A payout goes requested → approved → paid, or can be rejected before it's paid. One method lists the legal moves, so trying "mark paid" on a rejected payout throws an error instead of corrupting the balance.

**Q: Why store money in paise as integers?**
A: Floating-point numbers can't represent most decimals exactly — `0.29 * 100` is `28.999999999999996`. Integers of the smallest unit are exact. Even the form the creator types into converts "0.29" to 29 with string maths rather than multiplying a float.

### Intermediate
**Q: Two requests arrive at the same instant asking for more than the balance combined. What stops both succeeding?**
A: A row lock. `requestPayout` first does `SELECT … FOR UPDATE` on the creator's profile row. The second request waits at that line until the first commits, and then its check runs and sees the first request's open row, so it is refused. As a backstop there's a partial unique index in the database allowing only one open request per creator. And there's a test with two threads released by a latch that asserts exactly one wins.

**Q: Why lock the profile row rather than the payouts table?**
A: A table lock would make every creator wait for every other. The profile row is one-per-creator, so only that creator's requests queue behind each other, and it exists before any payout row does — you can't lock a row that hasn't been inserted yet.

**Q: The admin marks a payout paid. Can the app verify the money moved?**
A: No, and I'd say so plainly. Payouts are manual: the admin transfers by UPI outside the app and records the transaction id. The app records who did it, when, and the reference in the audit log, but "paid" is the admin's assertion. Automating that is RazorpayX, which needs Phase 18's business account.

**Q: How do you stop CSV injection in the statement download?**
A: Product titles are creator-controlled, and a cell starting with `=`, `+`, `-` or `@` runs as a formula in Excel. Every text cell is quoted and anything starting with one of those characters gets a leading apostrophe so the spreadsheet treats it as text. Amounts are integer paise so nothing gets rounded.

**Q: How do you make sure a creator can only download their own statement?**
A: The URL has no creator id. The controller reads the id from the authenticated JWT, and `SecurityConfig` restricts `/api/creator/**` to the CREATOR role, so a buyer gets 403 and an anonymous caller 401. There's nothing in the URL to change.

### Advanced / follow-up probes
**Q: Postgres refused your migration with "unsafe use of new value". Explain.**
A: Flyway wraps each migration in a transaction, and a newly added enum value isn't usable until that transaction commits. If one file both adds `PAYOUT_REQUESTED` and inserts a row using it, it fails. So the `ADD VALUE` lives alone in its own migration, and nothing in it uses the value. (Enum values also can't be dropped, which is the case for `VARCHAR + CHECK` when the set changes often.)

**Q: A creator is paid out, then a buyer's refund is approved. What happens?**
A: It can't happen for the same sale in normal use, because payouts only draw on earnings older than the refund window. But the window is a policy, not a law of nature — an admin can refund later, or the gateway can process a dispute. Then `cleared` drops below what was committed, and we show available as zero rather than negative. The shortfall is a manual recovery. The honest fix is to record it as an explicit "adjustment" debit row so the ledger shows it and it nets off the next payout.

**Q: Why is `orders.completed_at` a new column instead of using `updated_at`?**
A: `updated_at` moves whenever anything writes to the row, so it would restart the hold clock. `completed_at` is set once, when the order transitions to COMPLETED, and means exactly one thing. It also survives the later REFUNDED transition. We backfilled existing rows from `updated_at`.

**Q: How would you test a race condition deterministically?**
A: Threads plus a `CountDownLatch`. Both threads finish setting up and wait on a "go" latch; the main thread releases it, so both hit `requestPayout` together. Each asks for an amount that fits the balance alone but not together, so the assertion is exact: one success, one refusal, one open row. It exercises the real Postgres lock via Testcontainers, not a mock.

**Q: What is the scoping bug in the print CSS and how would you have caught it earlier?**
A: A print rule intended for one page was written against bare `body`, so it applied site-wide. Nothing failed, because nobody prints in an automated test. We now assert on the stylesheet text (no bare `body` inside `@media print`) and test the class lifecycle in the hook and in the reader page. The general lesson: global CSS needs a scope, and "someone will notice" isn't a test.

### "Tell me about a bug you fixed"
**Q: Tell me about a bug you fixed.**
A: The reader-blocking print rule from an earlier phase was written for the whole site, so buyers couldn't print receipts or even the Terms page — it printed blank. I found it while specifying receipts. The fix was to scope the rule to `body.sl-reading`, add and remove that class from the reader with a hook that cleans up on unmount, mark the site chrome `.no-print`, and add three tests: the stylesheet has no bare `body` under `@media print`, the hook sets and clears the class, and the real reader page does too. I learned that global rules need an owner and a scope, and that a security-ish control should have a regression test that fails if it widens.

---

## 8. Gotchas and bugs we hit

| Symptom | Root cause | Fix | Lesson |
|---|---|---|---|
| Nothing could be printed (receipts, legal pages) | `@media print { body { display:none } }` from Phase 05B applied site-wide | Scope to `body.sl-reading`, toggled by `useReadingMode` | Scope global CSS; test it |
| A creator without a `creator_profiles` row (test fixtures; any role granted outside `becomeCreator`) made `SELECT … FOR UPDATE` find nothing to lock | `becomeCreator` creates the profile, but nothing in the schema guarantees one exists for every CREATOR | `INSERT … ON CONFLICT DO NOTHING` before the lock | You can't lock a row that doesn't exist; make it exist first, idempotently |
| Receipt "not found" tests failed: expected `RESOURCE_NOT_FOUND` | The GraphQL error extension is `NOT_FOUND` (the exception handler maps it) | Assert `NOT_FOUND` | Read the real error contract before writing the assertion |
| CSS-scoping test saw an empty stylesheet | Vitest doesn't process CSS, so `import x from './index.css?raw'` is `''`; and jsdom's `import.meta.url` isn't a `file:` URL | Read `src/index.css` with `fs.readFileSync` (vitest runs from `frontend/`) | Test what you think you're testing; an empty string matches nothing and should have made you suspicious |
| `react-refresh/only-export-components` lint warning | Exported constants alongside components in `LegalPage.tsx` / `SiteFooter.tsx` | Moved constants to `content/legal/footerLinks.ts` and `lib/legalReview.ts` | Keep component files component-only |
| `ProductRecoveryIT.retryProcessing…` timed out once during the full `verify` run | A pre-existing timing-sensitive test of the async pipeline; it passes on its own (re-run) and does not touch payouts | Re-ran it; no code change | Flaky async tests are noise until proven otherwise — but say so, don't hide it (§10) |
| An enum value can't be used in the migration that adds it | Postgres "unsafe use of new value" inside a transaction | Own migration (`V11`) | Read the docs for `ALTER TYPE … ADD VALUE` before you write it |

---

## 9. New vocabulary

| Term | One-line meaning |
|---|---|
| **Ledger** | A record of events (credits and debits) from which balances are computed, rather than a stored balance |
| **Hold period** | Days earnings stay "pending" before they can be withdrawn (here 7, matching the refund window) |
| **Available / pending / lifetime / paid out** | The four balance figures: withdrawable now / still on hold / all non-refunded earnings / already sent |
| **Check-then-act race** | Two concurrent requests both pass a check before either performs the action |
| **Row lock (`SELECT … FOR UPDATE`)** | Makes other transactions wait for that row until you commit |
| **Partial unique index** | A uniqueness rule applying only to rows matching a `WHERE` — used for "one open payout per creator" |
| **Snapshot** | Copying a value onto a record at the moment it matters so later edits don't rewrite history |
| **Manual payout** | Money is sent outside the app (UPI/bank) and recorded in the app afterwards |
| **`Long` scalar** | The GraphQL scalar for 64-bit integers (GraphQL's own `Int` is 32-bit) |
| **CSV injection** | Malicious spreadsheet formulas hidden in exported data (`=`, `+`, `-`, `@` prefixes) |
| **`ALTER TYPE … ADD VALUE`** | Adds a value to a Postgres enum; can't be used in the same transaction |
| **DPDP Act 2023** | India's Digital Personal Data Protection Act — consent, purpose limitation, deletion rights |
| **Data fiduciary** | DPDP's term for whoever decides why and how personal data is processed |
| **`.no-print` / `body.sl-reading`** | The two print-related CSS hooks: hide chrome on paper; blank the page while the secure reader is open |

---

## 10. If I had to defend this in a code review

- **Strongest points:** the balance can't drift because it is never stored; the payout request is race-safe (row lock + unique index + a real two-thread test); every admin transition is guarded, audited and notified in one transaction; the print bug is fixed at its root and locked in with regression tests.
- **Weakest point, fix first:** a refund *after* a payout can leave a creator owing money, and today the app only clamps "available" to zero — the debt itself isn't recorded. The fix is an explicit **adjustment** ledger entry (a negative payout-like row) so the shortfall is visible and nets off the next payout.
- **Also weak:** the 7-day figure lives in two places (`REFUND_WINDOW_DAYS` in the frontend text and `payouts.hold-days` on the server); changing one without the other makes the policy text lie. "Paid" is the admin's word — the app can't verify the transfer. And the legal text is a *template*; nothing here has been reviewed by a lawyer, which is why the banner exists.
- **Not verified:** the concurrent-request test proves the lock against a real Postgres, but I did not load-test it; the CSV opens correctly per RFC 4180 but I did not open it in Excel; the receipt's print layout was reasoned through (`.no-print`, `print:` utilities) and unit-tested for classes, not printed on paper.
