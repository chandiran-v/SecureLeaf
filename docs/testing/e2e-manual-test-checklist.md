# SecureLeaf — End-to-End Manual Test Checklist

> **Purpose:** test every phase by hand, from Phase 1 to Phase 17, before 09E (Go live).
> **Branch under test:** `feature/secure-leaf-mvp2` (contains MVP1 + MVP2 Phases 10–13, 15–17).
> **Phase 14 (libvips) is parked and not built**, so it has no section.
>
> Each check has an **ID** (e.g. `5.12`). When something fails, log it in [§ Bug log](#bug-log) with that ID
> and open a GitHub Issue labelled `fix` + `mvp2`. The scheduler fixes `fix` Issues before anything else.
>
> Requirement IDs (`AUTH-01`, `VIEW-03` …) link back to [`docs/requirements.md`](../requirements.md).
> The automated evidence for each one is in [`release-mvp1.md`](../release-mvp1.md) and
> [`release-mvp2.md`](../release-mvp2.md); this file is the **human** pass over the same ground.

**Legend:** `- [ ]` not run · `- [x]` pass · mark failures with ❌ and a bug-log ID · ⚙️ = needs a config change or a tool

---

## Contents

0. [Setup](#0-setup) · [Automated suites](#a-run-the-automated-suites-first)
1. [Auth](#phase-1--auth--user-management) · 2. [Upload](#phase-2--upload--processing-pipeline) · 3. [Marketplace](#phase-3--marketplace--free-preview) · 4. [Commerce](#phase-4--commerce-mock-gateway)
5. [Secure viewer](#phase-5-a--b--secure-viewer) · 6. [Library / dashboard / notifications](#phase-6--library-creator-dashboard-live-notifications) · 7. [Reviews / password reset](#phase-7--reviews--ratings--password-reset) · 8. [Admin](#phase-8--admin-panel)
9. [Hardening](#phase-9--hardening--release-readiness) · 9B. [Razorpay](#phase-09b--razorpay-test-mode) · 9C. [Payouts / receipts / legal](#phase-09c--payouts-receipts-legal-pages) · 9D. [Prod stack](#phase-09d--production-stack-local-rehearsal)
10. [Observability](#phase-10--observability--load-test-harness) · 11. [Rate limiting](#phase-11--per-buyer-rate-limiting) · 12. [Render pool](#phase-12--render-pool--backpressure) · 13. [Tile cache](#phase-13--watermarked-tile-cache)
15. [Versioning](#phase-15--document-versioning) · 16. [Adaptive tiles](#phase-16--adaptive-tile-resolution) · 17. [Capacity](#phase-17--capacity-verification)
X. [Cross-cutting](#x--cross-cutting-checks) · [Bug log](#bug-log) · [Sign-off](#sign-off)

---

## 0. Setup

### 0.1 Start the stack

```powershell
# Terminal 1: infra (Postgres, Redis, MinIO, Mailpit, pgAdmin) + Prometheus/Grafana
docker compose -f infra/docker-compose.yml --profile monitoring up -d

# Terminal 2: backend (PowerShell). PAYOUT_HOLD_DAYS=0 makes sales payable at once (Phase 09C).
cd backend
$env:ADMIN_EMAILS="admin@test.local"; $env:PAYOUT_HOLD_DAYS="0"
./mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=dev"

# Terminal 3: frontend
cd frontend; npm install; npm run dev
```

Git Bash equivalent for the backend: `ADMIN_EMAILS=admin@test.local PAYOUT_HOLD_DAYS=0 ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`

- [ ] **0.1.1** Every container is `healthy`: `docker compose -f infra/docker-compose.yml ps`
- [ ] **0.1.2** Backend log shows Flyway migrations applied and `Started …Application`, with no stack traces
- [ ] **0.1.3** Frontend opens at <http://localhost:5173>

### 0.2 Tools you'll use

| Tool | URL | Used for |
|---|---|---|
| App | <http://localhost:5173> | Everything a user does |
| GraphiQL | <http://localhost:8080/graphiql> | Operations that have **no UI** (refund, backfill, scrapers) and negative tests |
| Mailpit | <http://localhost:8025> | Every email the app sends |
| MinIO console | <http://localhost:9001> (`secureleaf_minio_user` / `secureleaf_minio_pass`) | Buckets, tiles, privacy |
| pgAdmin | <http://localhost:5050> (or `docker exec -it <postgres-container> psql -U <user> secureleaf`) | DB verification queries |
| Grafana | <http://localhost:3001> (admin / admin) | Phases 10–13, 17 |
| Prometheus | <http://localhost:9090> | Raw metrics |
| Redis CLI | `docker exec -it <redis-container> redis-cli` | Sessions, rate-limit keys |

**Using GraphiQL as a logged-in user:** log in to the app, open DevTools → *Application* → *Local Storage* →
copy `accessToken`. In GraphiQL's **Headers** tab paste `{"Authorization": "Bearer <token>"}`.
Access tokens last **15 minutes**; copy a fresh one when you get `UNAUTHENTICATED`.

**DevTools and the reader:** the reader blurs when it detects **docked** DevTools (VIEW-08, Phase 5).
When you need the Network tab inside the reader, **undock DevTools into a separate window** (⋮ → Dock side → separate window).

### 0.3 Test accounts (create them as you go)

| Account | Email | Role | Notes |
|---|---|---|---|
| Admin | `admin@test.local` | ADMIN | In `ADMIN_EMAILS`, so it becomes admin at registration |
| Creator A | `creator.a@test.local` | Creator | Owns most products |
| Creator B | `creator.b@test.local` | Creator | For "can't touch someone else's product" checks |
| Buyer 1 | `buyer1@test.local` | Buyer | Main buyer |
| Buyer 2 | `buyer2@test.local` | Buyer | Second buyer (versioning, isolation) |
| Google user | your Gmail | Buyer | Only if Google sign-in is configured (⚙️ `GOOGLE_CLIENT_ID` + `VITE_GOOGLE_CLIENT_ID`) |

Use **one browser profile per account** (e.g. Chrome normal + Chrome incognito + Edge) so sessions don't overwrite each other.

### 0.4 Test files to prepare

| File | How to get it | Used in |
|---|---|---|
| `small.pdf` (3 pages) | `e2e/fixtures/sample.pdf` | Most flows |
| `long-links.pdf` (10+ pages, with a web link and a "go to page" link, e.g. a table of contents) | Export any Word/Google doc with a TOC as PDF | Navigation, zoom, PDF links |
| `v2.pdf` | A PDF that **looks clearly different** from `small.pdf` (e.g. a different first-page title) | Phase 15 versioning |
| `fake.pdf` | Rename any `.jpg` or `.txt` to `.pdf` | Magic-byte rejection |
| `huge.pdf` (> 50 MB) | Any large PDF, or a scanned book | Size limit |
| `broken.pdf` | Take a real PDF and cut it in half: `head -c 20000 small.pdf > broken.pdf` (Git Bash) | Processing failure + retry |

---

## A. Run the automated suites first

If any of these fail, fix that first. A manual pass on a red build wastes time.

- [ ] **A.1** Backend unit + integration tests (needs Docker for Testcontainers): `cd backend; ./mvnw.cmd verify` → `BUILD SUCCESS`
- [ ] **A.2** Frontend: `cd frontend; npm run lint; npx tsc --noEmit; npm run test -- --run; npm run build` → all pass
- [ ] **A.3** Playwright golden path (stack from §0.1 running): `cd e2e; npm install; npx playwright install chromium; npm run e2e` → 1 passed
- [ ] **A.4** k6 smoke (Phase 17 regression guard). See [`loadtest/README.md`](../../loadtest/README.md) "Smoke test" → thresholds pass
- [ ] **A.5** Load-test scripts are valid: `node --check loadtest/capacity.js` and the same for `viewer.js`, `scraper.js`, `smoke.js`

---

## Phase 1 — Auth & user management

**Requirements:** AUTH-01..06 · **Note:** [`phase-01-auth.md`](../learning-notes/phase-01-auth.md)

**Registration & login (AUTH-01, AUTH-03)**
- [ ] **1.1** Register Buyer 1 with a valid email + strong password → logged in, header shows the user
- [ ] **1.2** Register with an invalid email, a weak/short password, or empty fields → clear field errors, nothing is created
- [ ] **1.3** Register again with Buyer 1's email → "email already in use" error (not a crash)
- [ ] **1.4** Log out → protected pages (`/library`) now redirect to `/login`
- [ ] **1.5** Log in with a wrong password → one generic error; the message must **not** say whether the email exists
- [ ] **1.6** Log in correctly → back in; reload the page → still logged in (token persisted)

**Tokens (AUTH-03, AUTH-04)**
- [ ] **1.7** In DevTools → Local Storage, change `accessToken` to garbage and click around → the app refreshes the token silently **or** shows the *Session expired* modal; never a blank page or a raw error
- [ ] **1.8** ⚙️ GraphiQL: call `login`, keep the `refreshToken`; call `refreshToken` with it → new pair. Call `refreshToken` **again with the old one** → rejected (`TOKEN_REUSE`), and the newer token is revoked too
- [ ] **1.9** DB: `SELECT * FROM refresh_tokens ORDER BY id DESC LIMIT 3;` → only a **hash** is stored, never the raw token

**Google sign-in (AUTH-02)** ⚙️ *skip if Google isn't configured; note that this has no automated test*
- [ ] **1.10** "Sign in with Google" → account created, logged in
- [ ] **1.11** Sign out and sign in with Google again → the same account, no duplicate user row

**Become a creator (AUTH-06)**
- [ ] **1.12** As Creator A: `/become-creator` → fill bio + payout UPI or email → becomes a creator; creator nav (Dashboard / Upload) appears
- [ ] **1.13** Submit with neither payout UPI nor payout email → validation error (or note what happens)
- [ ] **1.14** Repeat for Creator B

**Role-based access (AUTH-05)**
- [ ] **1.15** As Buyer 1, type `/creator`, `/creator/upload` and `/admin` in the address bar → redirected or "not allowed"; no creator/admin data visible
- [ ] **1.16** As a guest, open `/library`, `/read/1` and `/checkout/1` → sent to login

---

## Phase 2 — Upload & processing pipeline

**Requirements:** UPLOAD-01..11 · **Note:** [`phase-02-upload-pipeline.md`](../learning-notes/phase-02-upload-pipeline.md)

**Happy path (UPLOAD-01, 04, 05, 06, 08)**
- [ ] **2.1** Creator A → Dashboard → **Upload new product** button (DASH-04) → opens `/creator/upload`
- [ ] **2.2** Fill title, description, category, tags, price ₹199, free preview pages = 1, file `small.pdf` → submit
- [ ] **2.3** Dashboard shows the product as **PROCESSING with the current stage** (Validate → Convert → Thumbnail → Preview → Live), then **LIVE** without a manual refresh (or after one)
- [ ] **2.4** A cover image appears; it's auto-generated from page 1 (by design, see release-mvp1 UPLOAD-05)
- [ ] **2.5** The bell gets a **"processing complete"** notification (NOTIF-03)
- [ ] **2.6** Upload `long-links.pdf` as a second product, price ₹99, free preview = 2
- [ ] **2.7** Upload a **free** product (₹0, UPLOAD-07) using `small.pdf`

**Validation (UPLOAD-02, 03)**
- [ ] **2.8** Upload `fake.pdf` → rejected with a clear "not a PDF" message (the server checks the file's magic bytes, not the extension)
- [ ] **2.9** Upload `huge.pdf` (> 50 MB) → rejected with "file too large" (**no automated test exists for this**)
- [ ] **2.10** Submit with missing title / negative price / free-preview pages greater than the page count → validation errors

**Failure & retry (UPLOAD-10)**
- [ ] **2.11** Upload `broken.pdf` → after retries it becomes **FAILED** with a failure reason visible on the dashboard
- [ ] **2.12** The bell shows **"processing failed"**
- [ ] **2.13** DB: `SELECT status, retry_count, started_at, failure_reason FROM processing_jobs ORDER BY id DESC LIMIT 5;` → the failed job shows `retry_count` = 3; `started_at` is set on claimed jobs (09D D13)

**Storage privacy (UPLOAD-11)**
- [ ] **2.14** MinIO console: `secureleaf-raw`, `secureleaf-tiles` and `secureleaf-thumbnails` exist and access policy is **private**
- [ ] **2.15** Copy any object path and open `http://localhost:9000/secureleaf-tiles/<object-key>` in a browser with no credentials → `AccessDenied`
- [ ] **2.16** *(optional)* Upload 4–5 PDFs at once from separate tabs → all reach LIVE (NFR: 10+ simultaneous uploads)

---

## Phase 3 — Marketplace & free preview

**Requirements:** MARKET-01..07 · **Note:** [`phase-03-marketplace.md`](../learning-notes/phase-03-marketplace.md)

- [ ] **3.1** As a **guest**, `/marketplace` lists LIVE products only; the FAILED product from 2.11 is **not** shown (MARKET-01)
- [ ] **3.2** Search by a word in a **title**, then a **creator name**, then a **tag** → correct results; nonsense query → friendly empty state (MARKET-02)
- [ ] **3.3** Filter by category, Free, Paid, price range → correct, and filters combine (AND) (MARKET-03)
- [ ] **3.4** Sort: Newest, Most Popular, Highest Rated, Price low → high → order is correct (MARKET-04)
- [ ] **3.5** With more products than one page holds, pagination works and the last page shows the remainder (MARKET-07). Lower the page size or upload more if needed
- [ ] **3.6** Product detail page shows cover, title, description, creator, price, rating, buyer count, preview (MARKET-05)
- [ ] **3.7** Free preview: the first N pages are readable **with a watermark**; pages after N are blurred / locked (MARKET-06, UPLOAD-08)
- [ ] **3.8** DevTools → Elements: preview pages are drawn on a `<canvas>`, not an `<img>`
- [ ] **3.9** Network tab: preview responses are PNGs from `/api/products/{id}/preview/{page}`; requesting page N+1 directly by URL → 404

---

## Phase 4 — Commerce (mock gateway)

**Requirements:** PAY-01..10 · **Note:** [`phase-04-commerce.md`](../learning-notes/phase-04-commerce.md)
Default dev gateway is `mock`. The checkout page has three buttons: **Pay**, **Simulate decline**, **Simulate timeout**.

- [ ] **4.1** As a guest, click **Buy** → redirected to login, then back to the product (PAY-01)
- [ ] **4.2** As Buyer 1, buy product #1 → `/checkout/:orderId` → **Pay (success)** → confirmation; product is in **My Library** (PAY-03, 05, 09)
- [ ] **4.3** Buyer 1's bell: "purchase successful" (NOTIF-01). Creator A's bell: "sale received" (NOTIF-02)
- [ ] **4.4** Mailpit: a purchase email reached Buyer 1 (PAY-08). *There's no automated test for this email*
- [ ] **4.5** Product page now shows **Read now** instead of Buy
- [ ] **4.6** Buyer 1 buys product #2 → **Simulate decline** → error shown, **no** library entry, can try again and succeed
- [ ] **4.7** Buyer 2 buys product #2 → **Simulate timeout** → browser shows a timeout/pending state; within ~5 s the webhook completes it. Refresh the library → product present
- [ ] **4.8** Double-click **Buy** quickly, or open checkout in two tabs and pay in both → exactly **one** order and **one** entitlement (PAY-04, PAY-07)
- [ ] **4.9** DB, after 4.2–4.8:
  ```sql
  SELECT user_id, product_id, status, count(*) FROM entitlements GROUP BY 1,2,3 HAVING count(*) > 1;  -- expect 0 rows
  SELECT * FROM payment_events ORDER BY id DESC LIMIT 10;  -- PENDING → COMPLETED trail per order
  ```
- [ ] **4.10** Mock webhooks are sent **twice** on purpose (`payment.mock.duplicate-webhooks`). Check `payments` → still one row per order
- [ ] **4.11** `UPDATE payment_events SET event_source = event_source WHERE id = (SELECT max(id) FROM payment_events);` → **error** (append-only trigger, PAY-06)
- [ ] **4.12** Get the free product → completes **without** a payment step; it's in the library (UPLOAD-07)
- [ ] **4.13** Creator A's dashboard earnings: for one ₹199 sale, gross ₹199, platform fee ₹9.95, net ₹189.05 (PAY-10, 5% commission)
- [ ] **4.14** Creator A opens their own product page → note what happens with Buy (expected: can't buy your own product, or a clear message)

---

## Phase 5 (A + B) — Secure viewer

**Requirements:** VIEW-01..13 · **Note:** [`phase-05-secure-viewer.md`](../learning-notes/phase-05-secure-viewer.md)
Use Buyer 1 with product #2 (`long-links.pdf`). **Undock DevTools** before opening Network (§0.2).

**Rendering & URLs (VIEW-01, 02, 03)**
- [ ] **5.1** Library → **Read** → `/read/:productId` opens full-screen; page 1 renders sharply
- [ ] **5.2** Elements: the page is a `<canvas>`; **no `<img>`, no `blob:` URL, no CSS background** carries the page
- [ ] **5.3** The watermark is visible and reads like `buyer1@test.local · #<id> · <yyyy-MM-dd> UTC · s<sessionId>` (date only since Phase 13)
- [ ] **5.4** Network: tile requests look like `/api/viewer/tiles/{sessionId}/{page}?exp=…&sig=…&variant=…`; response headers include `Cache-Control: no-store, private` and **no** `Content-Disposition`
- [ ] **5.5** Copy one tile URL and replay it with the same token (DevTools → *Copy as fetch*, run in the console) → **403** (single-use)
- [ ] **5.6** Open the same tile URL in a new tab (no `Authorization` header) → **401**
- [ ] **5.7** Wait > 30 s after the URL was issued and replay it → **403** (expired)
- [ ] **5.8** Change one digit of `page`, `sessionId` or `sig` → **403**
- [ ] **5.9** Replay a URL issued to Buyer 1 with **Buyer 2's** token → **403**

**Piracy friction (VIEW-04..09)**
- [ ] **5.10** Right-click on the page → no context menu (VIEW-04)
- [ ] **5.11** Try to select text / Ctrl+A → nothing selectable (VIEW-05)
- [ ] **5.12** Try to drag the page to the desktop → nothing (VIEW-06)
- [ ] **5.13** Ctrl+P, Ctrl+S, and the browser menu → Print → blocked, or the print preview is **blank** (VIEW-07)
- [ ] **5.14** Switch tab or Alt+Tab away → page **blurs** with an overlay; come back → clear (VIEW-09)
- [ ] **5.15** Open **docked** DevTools (F12) → page blurs within ~1 s; close → clear (VIEW-08; heuristic, undocked won't trigger)
- [ ] **5.16** Press PrintScreen → canvas briefly blanks (best effort)

**Navigation, zoom, links (VIEW-11 + later features)**
- [ ] **5.17** Next / Prev buttons and ← / → keys change pages; indicator shows `Page n / N`
- [ ] **5.18** On page 1, Prev is disabled; on the last page, Next is disabled (clamped)
- [ ] **5.19** URL updates with `?page=n`; reload → resumes on page n. Manually set `?page=999` → clamped to the last page
- [ ] **5.20** Zoom in/out with the toolbar, keyboard, Ctrl+mouse wheel, and pinch (touchpad); pan when zoomed
- [ ] **5.21** Click a **web link** inside the PDF → opens in a new tab. Click an **internal link** (TOC entry) → jumps to that page

**Sessions (VIEW-10, 12)**
- [ ] **5.22** Open the same book as Buyer 1 in a **second browser**. Within ~15 s the first shows *"This book was opened on another device or tab"* with **Read here instead**
- [ ] **5.23** Click **Read here instead** in the first browser → it takes over; the second browser now shows the panel
- [ ] **5.24** Close the reader tab. DB: `SELECT id, end_reason, ended_at FROM viewer_sessions ORDER BY id DESC LIMIT 3;` → `CLOSED` (and `SUPERSEDED` for 5.22)
- [ ] **5.25** Put the reader offline (DevTools → Network → Offline) for > 60 s, then back online → it silently restarts and keeps working (EXPIRED path)
- [ ] **5.26** As Buyer 2 (not entitled to product #1), open `/read/<product1>` → *"You don't have access"* panel with a link to the product page (VIEW-12)

**Audit (VIEW-13)**
- [ ] **5.27** Read pages 1–3. DB: `SELECT page_number, ip_address, correlation_id, variant, viewed_at FROM viewer_access_logs ORDER BY id DESC LIMIT 5;` → one row per page view; failed fetches (5.5–5.9) wrote **no** rows
- [ ] **5.28** The `X-Correlation-Id` response header of a tile matches the `correlation_id` in its log row
- [ ] **5.29** Reader at 360 px wide (resize the window, or a real phone) → usable, buttons tappable

---

## Phase 6 — Library, creator dashboard, live notifications

**Requirements:** LIB-01..03, DASH-01..04, UPLOAD-09/10, NOTIF-01..04 · **Note:** [`phase-06-…md`](../learning-notes/phase-06-library-dashboard-notifications.md)

**Library**
- [ ] **6.1** `/library` shows cover, title, creator, purchase date, status badge **Active**, and **Read** (LIB-01, 02, 03)
- [ ] **6.2** Search by title filters the list; a brand-new buyer sees an empty state linking to the marketplace
- [ ] **6.3** A revoked entitlement (do this after the refund in 9B.11) shows **Revoked** and a disabled Read with the reason

**Dashboard**
- [ ] **6.4** Creator A's dashboard lists every product with status PROCESSING / LIVE / UNPUBLISHED / FAILED (DASH-01)
- [ ] **6.5** Per-product **sales count** and **net earnings** are correct (DASH-02); earnings cards show gross / platform fee / net (DASH-03)
- [ ] **6.6** FAILED product (2.11) → **Retry** → back to PROCESSING (it will fail again with `broken.pdf`; that proves the button works)
- [ ] **6.7** ⚙️ GraphiQL as Creator B: `retryProcessing` or `unpublishProduct` on Creator A's product → `ACCESS_DENIED` (object-level authorisation)
- [ ] **6.8** **Unpublish** product #1 → a confirmation modal says existing buyers keep access → product disappears from the marketplace (UPLOAD-09)
- [ ] **6.9** Buyer 1 can still find it in the library and **read** it
- [ ] **6.10** **Republish** → back in the marketplace
- [ ] **6.11** **Delete** a product that has a buyer → confirmation → gone from the marketplace and dashboard; the buyer can still read it

**Real-time notifications (SSE)**
- [ ] **6.12** Creator A on the dashboard (browser 1); Buyer 2 buys one of Creator A's products (browser 2) → Creator A's bell updates **within ~5 s with no reload**, plus a toast
- [ ] **6.13** Network (creator's browser): one long-lived request `/api/notifications/stream?ticket=…`, type `eventsource`
- [ ] **6.14** Copy that stream URL and open it in a new tab → **401** (the ticket is single-use)
- [ ] **6.15** **Mark all read** → badge goes to 0 and stays 0 after a reload
- [ ] **6.16** Stop the backend for ~30 s and restart it → the bell reconnects by itself (new ticket); a new sale still arrives live

---

## Phase 7 — Reviews & ratings + password reset

**Requirements:** REV-01..04, MARKET-03, AUTH-07 · **Note:** [`phase-07-…md`](../learning-notes/phase-07-reviews-password-reset.md)

**Reviews**
- [ ] **7.1** Buyer 1 (entitled) → product page → review form → 4 stars + text → appears in the list; average and histogram update (REV-01, 02, 04)
- [ ] **7.2** Star input works with the keyboard (Tab to it, arrow keys, Enter)
- [ ] **7.3** Submit again → the form is prefilled; change to 2 stars → **edits** the same review; still one review (REV-03)
- [ ] **7.4** **Delete my review** → removed; the average recomputes (shows "no ratings" when none left)
- [ ] **7.5** Buyer 2 (not entitled) → no review form, or submitting is rejected
- [ ] **7.6** Creator A on their own product → can't review it
- [ ] **7.7** Text of 2,001 characters → rejected; 2,000 → accepted
- [ ] **7.8** Review text `<img src=x onerror=alert(1)><b>bold</b>` → shown as **literal text**; no alert, no bold (XSS check)
- [ ] **7.9** The review shows the reviewer's **display name only**; check the GraphQL response in Network: no email anywhere
- [ ] **7.10** Marketplace: rating filter (e.g. 4★ & up) hides lower-rated and unrated products; sort by rating puts unrated **last** (MARKET-03)
- [ ] **7.11** Two buyers submit reviews on the same product at nearly the same moment → both appear, `review_count` = 2 and the average is exact

**Password reset (AUTH-07)**
- [ ] **7.12** `/forgot-password` with an email that **doesn't exist** → the same generic "if an account exists…" message; Mailpit receives **nothing**
- [ ] **7.13** Same with Buyer 1's email → same message; Mailpit has **one** email with a reset link
- [ ] **7.14** Before resetting, keep Buyer 1 logged in on a second browser
- [ ] **7.15** Open the link → new password + mismatched confirmation → validation error; matching → success
- [ ] **7.16** Open the **same link again** → "invalid or expired" (single use)
- [ ] **7.17** Log in with the **old** password → fails; **new** password → works
- [ ] **7.18** The second browser from 7.14 is logged out within ≤ 15 min (its refresh token was revoked)
- [ ] **7.19** Request a reset 4 times in a row for the same email → still the generic message every time, but Mailpit gets **at most 3** emails (rate limit)
- [ ] **7.20** A Google-only account requests a reset → email says "you sign in with Google" (⚙️ only if Google is configured)

---

## Phase 8 — Admin panel

**Requirements:** ADMIN-01..04, AUTH-08 · **Note:** [`phase-08-admin-panel.md`](../learning-notes/phase-08-admin-panel.md)

- [ ] **8.1** Register `admin@test.local` → the **Admin** nav appears. Restart the backend → still admin, no duplicate role row (`SELECT * FROM user_roles WHERE …`)
- [ ] **8.2** Buyer and creator accounts never see the Admin nav; typing `/admin/*` URLs shows nothing useful
- [ ] **8.3** ⚙️ GraphiQL as Buyer 1: `adminUsers`, `platformStats`, `suspendUser` → all `ACCESS_DENIED`

**Users (ADMIN-01, 02, AUTH-08)**
- [ ] **8.4** `/admin/users`: search by part of an email and by display name (case-insensitive); filter by role and by status; pagination works
- [ ] **8.5** Product count and purchase count per user are correct
- [ ] **8.6** Buyer 1 is reading a book in another browser. Admin **suspends** Buyer 1 → the modal **requires a reason**
- [ ] **8.7** Buyer 1's next action fails at once (401 / logged out), and the open reader stops serving pages
- [ ] **8.8** Buyer 1 can't log in while suspended
- [ ] **8.9** **Reactivate** → Buyer 1 can log in fresh and read again
- [ ] **8.10** Admin tries to suspend **themselves** → rejected. Suspending another admin → rejected (register a second admin email to try, optional)

**Products (ADMIN-03)**
- [ ] **8.11** `/admin/products`: filter by status, creator and search
- [ ] **8.12** **Take down** product #2 with a reason → gone from the marketplace; Buyer 1 still has it in the library and **can still read**
- [ ] **8.13** Creator A tries **Republish** → refused (taken down by admin)
- [ ] **8.14** Admin **Restore** → back in the marketplace

**Analytics & audit (ADMIN-04)**
- [ ] **8.15** `/admin` dashboard: total users, creators, LIVE products, completed orders, gross sales, platform fees, top products → **match your own tally** of what you created so far
- [ ] **8.16** `/admin/audit-log` shows exactly one row per admin action you did (suspend, reactivate, takedown, restore…), with the reason
- [ ] **8.17** `UPDATE admin_actions SET reason = 'x' WHERE id = (SELECT max(id) FROM admin_actions);` → **error** (append-only)

---

## Phase 9 — Hardening & release readiness

**Requirements:** non-functional · **Note:** [`phase-09-hardening-release.md`](../learning-notes/phase-09-hardening-release.md)

- [ ] **9.1** Any API response (Network tab) has an `X-Correlation-Id` header
- [ ] **9.2** `curl -i -H "X-Correlation-Id: test-123" http://localhost:8080/actuator/health` → echoed back as `test-123`
- [ ] **9.3** Trigger an error (e.g. decline a payment) → the error toast shows **"Reference: …"**; that id is findable in the backend log
- [ ] **9.4** `curl -i http://localhost:8080/actuator/health` → `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, `Content-Security-Policy`, `Permissions-Policy`, `X-Content-Type-Options: nosniff`
- [ ] **9.5** ⚙️ GraphiQL: send a query nested more than 10 levels deep → rejected (depth limit). *(The `products → creator → …` chain, repeated, works for this)*
- [ ] **9.6** Log in with a wrong password **5 times**, then a 6th time → **"too many attempts"** (`RATE_LIMITED`), even with the right password
- [ ] **9.7** On a different email (or after 15 min), a correct login resets the counter
- [ ] **9.8** `curl http://localhost:8080/actuator/health/readiness` → `UP`. `docker stop <redis-container>` → `DOWN`. Start Redis → `UP` again
- [ ] **9.9** While Redis is stopped: login still works (suspension check fails **open** by design), viewer and notifications degrade with friendly errors, not crashes
- [ ] **9.10** Search the backend log for your test emails, `Bearer`, `sig=` and `password` → **no** secrets or emails logged (dev SQL logging aside)

---

## Phase 09B — Razorpay (test mode)

**Note:** [`phase-09b-razorpay.md`](../learning-notes/phase-09b-razorpay.md)
⚙️ Needs a free Razorpay account in **test mode**. Restart the backend with
`PAYMENT_GATEWAY=razorpay`, `RAZORPAY_KEY_ID=rzp_test_…`, `RAZORPAY_KEY_SECRET=…`, `RAZORPAY_WEBHOOK_SECRET=<any string>`.
Webhooks can't reach `localhost` without a tunnel (ngrok / cloudflared). Without one, the browser's
`verifyPayment` and the reconciliation job still complete orders; the webhook paths get a full test on the live server in 09E.

**Demo mode**
- [ ] **9B.1** A site-wide banner: "Demo mode — payments use Razorpay test mode. No real money is charged." (also in `mock` mode)
- [ ] **9B.2** Dismiss it → stays hidden for this browser session; a new session shows it again; it's **always** shown on the checkout page
- [ ] **9B.3** Checkout shows the test hints panel (test cards link, `success@razorpay`)

**Payments**
- [ ] **9B.4** Buy → the Razorpay modal opens with the right amount, "SecureLeaf", the product title, and your email prefilled
- [ ] **9B.5** Pay with UPI `success@razorpay` (or a test card) → entitlement granted, library updated, notifications sent
- [ ] **9B.6** Pay with UPI `failure@razorpay` → Razorpay's error description shown; no entitlement
- [ ] **9B.7** Close the modal without paying → **"Payment cancelled"**; you can retry
- [ ] **9B.8** DB: `SELECT gateway_fee_paise, gateway_tax_paise FROM payments ORDER BY id DESC LIMIT 1;` → filled from Razorpay
- [ ] **9B.9** Razorpay dashboard (test mode) shows the payment as **captured**
- [ ] **9B.10** Admin dashboard shows gateway fees and platform net (platform fee − gateway fee − tax)

**Refunds** *(no UI: GraphiQL as admin)*
- [ ] **9B.11** `mutation { refundOrder(orderId: "<id>", reason: "test") { id status } }` → `REFUNDED`
- [ ] **9B.12** Buyer's library shows **Revoked**; opening the reader → "no access"; buyer gets a notification and an email
- [ ] **9B.13** Creator A's earnings and sales drop by that sale; the audit log has a refund row
- [ ] **9B.14** Same mutation as Buyer 1 → `ACCESS_DENIED`

**Guards**
- [ ] **9B.15** Start the backend with `RAZORPAY_KEY_ID=rzp_live_dummy` → it **refuses to start** with a clear message about live keys
- [ ] **9B.16** Switch back to `PAYMENT_GATEWAY=mock` → the mock flow (Phase 4) still works

---

## Phase 09C — Payouts, receipts, legal pages

**Note:** [`phase-09c-payouts-legal.md`](../learning-notes/phase-09c-payouts-legal.md) · Backend running with `PAYOUT_HOLD_DAYS=0` (§0.1)

**Balance & requests (creator)**
- [ ] **9C.1** Creator A → dashboard **Payouts** tab → balance card: available, pending, lifetime, paid out → match the sales so far (refunded sales excluded)
- [ ] **9C.2** Edit payout details (UPI / email) → saved
- [ ] **9C.3** Request **₹50** → rejected (minimum ₹100)
- [ ] **9C.4** Request more than the available balance → rejected
- [ ] **9C.5** Request a valid amount → status **REQUESTED**; admins get a notification
- [ ] **9C.6** Request again while one is open → rejected ("one open request at a time")
- [ ] **9C.7** Restart with `PAYOUT_HOLD_DAYS=7` → today's sales move to **pending**, not available. Switch back to 0 afterwards

**Admin workflow**
- [ ] **9C.8** `/admin/payouts` → **Approve** → APPROVED; creator notified + emailed
- [ ] **9C.9** **Mark paid** requires a reference (e.g. a UPI transaction id) → PAID; the creator's "paid out" goes up; available never goes back up
- [ ] **9C.10** New request → **Reject** with a reason → REJECTED; the amount is available again
- [ ] **9C.11** Each admin step wrote one audit-log row

**Statements**
- [ ] **9C.12** **Statements** tab → pick this month → lines for sales, refunds, fees, payouts; totals add up
- [ ] **9C.13** Download the CSV → opens in Excel, same totals
- [ ] **9C.14** As Creator B, open Creator A's CSV URL (`/api/creator/statements/<yyyy-mm>.csv` with B's token) → **403**, or B's own (empty) data, never A's

**Receipts**
- [ ] **9C.15** Purchase email links to `/orders/:id/receipt` → shows order id, date, product, creator, amount, **masked** payment id, and test/live mode
- [ ] **9C.16** Ctrl+P on the receipt → the print preview **shows the receipt**
- [ ] **9C.17** Buyer 2 opens Buyer 1's receipt URL → denied
- [ ] **9C.18** Regression: Ctrl+P / Print inside the **reader** → still blank (VIEW-07)

**Legal pages**
- [ ] **9C.19** Footer on every page links to Terms, Privacy, Refund policy, Contact, About; all open
- [ ] **9C.20** Each shows **"Draft template — not legal advice…"**
- [ ] **9C.21** Refund policy says **7 days**; Privacy mentions viewer access logs (IP, user agent, pages), the watermark containing the email, and the processors (Razorpay, Brevo, Sentry, Oracle Cloud)
- [ ] **9C.22** Contact shows the support email

---

## Phase 09D — Production stack (local rehearsal)

**Note:** [`phase-09d-production-single-server.md`](../learning-notes/phase-09d-production-single-server.md) · Run in **Git Bash** or WSL. Instructions in [`docs/deployment.md`](../deployment.md) ("Testing the stack").
This rehearses 09E on your laptop: if it works here, the Oracle deploy is mostly copy-paste.

- [ ] **9D.1** `docker compose -f infra/prod/docker-compose.prod.yml config` is valid; only `caddy` publishes ports
- [ ] **9D.2** `infra/prod/test/generate-env-test.sh` passes
- [ ] **9D.3** Bring up the prod stack with the test override (`tls internal`, `SITE_HOST=localhost`; command in the header of `infra/prod/test/stack-test.sh`)
- [ ] **9D.4** `infra/prod/test/stack-test.sh` passes (health, SPA, `/graphql`, headers, ports, SSE through Caddy)
- [ ] **9D.5** `infra/prod/scripts/smoke-watermark.sh` passes (fonts present in the backend image)
- [ ] **9D.6** Open `https://localhost:<HTTPS_PORT>` (accept the self-signed warning) → do a short manual journey: register, become a creator, upload, buy, read
- [ ] **9D.7** `infra/prod/test/backup-restore-test.sh` passes (data and objects match after a destroy + restore)
- [ ] **9D.8** *(optional)* Deploy-rollback test: point `deploy.sh` at a broken ref → the old version keeps serving and the script exits non-zero
- [ ] **9D.9** `docker stats` while reading → each service stays well under its memory limit (backend 4 GB, Postgres 1.5 GB, MinIO 1 GB, Redis 384 MB, Caddy 256 MB)

---

## Phase 10 — Observability & load-test harness

**Note:** [`phase-10-observability-load-testing.md`](../learning-notes/phase-10-observability-load-testing.md)

- [ ] **10.1** Read a few pages, then open `http://localhost:8080/actuator/prometheus` → contains `secureleaf_tile_request`, `secureleaf_watermark_render`, `secureleaf_storage_fetch`, `secureleaf_viewer_sessions_active`, `secureleaf_tile_bytes`
- [ ] **10.2** Grafana → **SecureLeaf — Viewer** dashboard: tile p50/p95/p99, render vs storage time, requests by outcome, threads, JVM heap/GC, active sessions all show data (no "No data" panels after a few page views)
- [ ] **10.3** Trigger a 403 (replay a tile URL) → the `outcome="forbidden"` series appears
- [ ] **10.4** Restart the backend with profile `dev,loadtest` and `LOADTEST_USERS=50` → log line `Load-test seed done`, `loadtest/users.csv` written. Restart again → nothing new created
- [ ] **10.5** Run `viewer.js` with `VUS=20` (command in `loadtest/README.md`) → completes, thresholds reported; Grafana panels move during the run

---

## Phase 11 — Per-buyer rate limiting

**Note:** [`phase-11-rate-limiting.md`](../learning-notes/phase-11-rate-limiting.md) · Limits: tile 2/s (burst 5), page-URL 4/s (burst 10), preview 1/s per IP (burst 20)

- [ ] **11.1** Read normally (one page every few seconds) for a couple of minutes → **never** a 429 or a "Slow down" hint
- [ ] **11.2** Hold → (arrow key) to flip pages as fast as possible → a subtle **"Slow down…"** hint, then pages continue loading by themselves (no error screen)
- [ ] **11.3** Network: the throttled responses are **429** with `Retry-After` and `X-RateLimit-Remaining`
- [ ] **11.4** While Buyer 1 is throttled, Buyer 2 reads normally in another browser → unaffected (per-user limits)
- [ ] **11.5** As a guest, reload a product's preview very fast (> 20 times) → preview requests get 429
- [ ] **11.6** `curl` the preview 25 times with `-H "X-Forwarded-For: 1.2.3.<n>"` (a different fake IP each time) → still throttled (XFF is ignored from untrusted sources)
- [ ] **11.7** Run `loadtest/scraper.js` (see the loadtest README) → mostly 429s, about 2 successful tiles/s
- [ ] **11.8** ⚙️ GraphiQL as admin: `suspectedScrapers(windowMinutes: 10)` → lists the scraper user after 11.7
- [ ] **11.9** Stop Redis briefly → tiles are still served (fails open); `secureleaf_ratelimit_redis_errors` increases in `/actuator/prometheus`

---

## Phase 12 — Render pool & backpressure

**Note:** [`phase-12-render-pool-backpressure.md`](../learning-notes/phase-12-render-pool-backpressure.md)

- [ ] **12.1** Normal reading → no difference from before (no 503s, no "Busy" hint)
- [ ] **12.2** `/actuator/prometheus` has `secureleaf_render_queue_size`, `secureleaf_render_active`, `secureleaf_render_wait`, `secureleaf_render_rejected`; Grafana shows the render pool panels
- [ ] **12.3** ⚙️ Restart with a tiny pool: `RENDER_POOL_SIZE=1`, `RENDER_POOL_QUEUECAPACITY=1`, then run `viewer.js` with ~20 VUs → some tile responses are **503 + `Retry-After: 1`**; `secureleaf_render_rejected` > 0
- [ ] **12.4** During 12.3, read in the browser → you may see **"Busy, retrying…"**, and the page still loads after retries; heartbeats and other GraphQL calls stay fast (the bulkhead works)
- [ ] **12.5** Remove the overrides and restart

---

## Phase 13 — Watermarked tile cache

**Note:** [`phase-13-watermarked-tile-cache.md`](../learning-notes/phase-13-watermarked-tile-cache.md)

- [ ] **13.1** Read pages 1 → 2 → 3, then go **back** to 2 → the back-navigation is noticeably faster; Grafana cache hit counter goes up
- [ ] **13.2** The cache directory (`%TEMP%\tile-cache` on Windows) contains files
- [ ] **13.3** A cached page still writes a `viewer_access_logs` row (a hit is still a view)
- [ ] **13.4** Start a **new session** (close and reopen the reader) → first view of each page is a miss (the key includes the session id)
- [ ] **13.5** Tiles in the browser are still `Cache-Control: no-store` (server-side cache only)
- [ ] **13.6** Refund or suspend a buyer who has cached pages → the next request is **403** even though a cache entry exists
- [ ] **13.7** ⚙️ Restart with `TILECACHE_TYPE=none` → everything works; no hits counted
- [ ] **13.8** *(optional)* `TILECACHE_TYPE=redis` → works the same; keys visible in Redis

---

## Phase 15 — Document versioning

**Note:** [`phase-15-document-versioning.md`](../learning-notes/phase-15-document-versioning.md)
Use a product Buyer 1 already owns (say product #1, `small.pdf`). Buyer 2 must **not** own it yet.

- [ ] **15.1** Creator A → product → **Versions** (`/creator/products/:id/versions`) → table shows v1: date, pages, status, policy, buyer count
- [ ] **15.2** Upload `v2.pdf` with policy **New buyers only** → while v2 is PROCESSING, the product stays **LIVE**, preview still shows v1
- [ ] **15.3** v2 finishes → the creator is notified; the product's preview now shows **v2**
- [ ] **15.4** Buyer 1 (bought v1) reads → still **v1** content
- [ ] **15.5** Buyer 1's library shows **"v1"** and **"A new edition is available"** linking to the product page
- [ ] **15.6** Buyer 2 buys now → reads **v2**; library shows "v2"
- [ ] **15.7** Upload `v3` (any different PDF) with policy **Free update for existing buyers** → after processing, Buyer 1 **and** Buyer 2 read v3; the version table's buyer counts move to v3
- [ ] **15.8** Upload `broken.pdf` as v4 → FAILED; product still LIVE on v3; **Retry** is offered on the failed version
- [ ] **15.9** Try to **retire** a version that buyers still hold → rejected. Retire one with no buyers → allowed (soft)
- [ ] **15.10** ⚙️ Creator B uploads a version to Creator A's product: `curl -X POST -H "Authorization: Bearer <B's token>" -F "file=@small.pdf" http://localhost:8080/api/products/<id>/versions` → **403**
- [ ] **15.11** DB: `SELECT id, current_document_version_id FROM products;` and `SELECT user_id, document_version_id, status FROM entitlements WHERE product_id = <id>;` → match what you saw in the UI

---

## Phase 16 — Adaptive tile resolution

**Note:** [`phase-16-adaptive-tile-resolution.md`](../learning-notes/phase-16-adaptive-tile-resolution.md) · Variants: DESKTOP 1240 px, MOBILE 900 px

- [ ] **16.1** Upload a new product → MinIO `secureleaf-tiles` has **both** DESKTOP and MOBILE tiles for every page; DB `SELECT document_version_id, page_number, variant FROM content_pages ORDER BY 1,2,3;`
- [ ] **16.2** Reader in a full-size window on a normal laptop screen → tile URLs carry `variant=DESKTOP`
- [ ] **16.3** Make the window narrow (~400 px) and reload, or use a phone → tile URLs carry `variant=MOBILE`; the image is 900 px wide (Network → Preview)
- [ ] **16.4** Resize the window while reading → the variant re-evaluates after a short pause (debounced)
- [ ] **16.5** The watermark is **equally legible** on MOBILE tiles (scaled font, min 12 px)
- [ ] **16.6** Change `variant=MOBILE` to `variant=DESKTOP` in a signed URL → **403** (variant is signed)
- [ ] **16.7** A product uploaded **before** Phase 16 (no MOBILE tiles) still reads on a narrow screen (falls back to DESKTOP, no error)
- [ ] **16.8** ⚙️ GraphiQL as admin: `mutation { generateMissingVariants }` → old products get MOBILE tiles in the background; running it again does nothing new. As Buyer 1 → `ACCESS_DENIED`
- [ ] **16.9** `viewer_access_logs.variant` records which variant was served

---

## Phase 17 — Capacity verification

**Note:** [`phase-17-capacity-verification.md`](../learning-notes/phase-17-capacity-verification.md) · Full method: [`docs/perf/capacity-report-mvp2.md`](../perf/capacity-report-mvp2.md)
The real 5,000-viewer run is an **owner-run on server hardware**, after go-live. On a laptop, just prove the tooling works.

- [ ] **17.1** `loadtest/smoke.js` passes (20 VUs, 60 s, p95 < 500 ms); this is the same as A.4
- [ ] **17.2** Grafana has the **Capacity** row (VUs vs p95, errors, CPU, threads, cache hit rate) and it fills during a run
- [ ] **17.3** *(optional, laptop-scale)* `capacity.js` with small VUs (e.g. 20 → 50 → 100, 1-minute holds) → finishes; compare with `docs/perf/ci-scale-results.md`
- [ ] **17.4** `node loadtest/heap-trend.test.js` passes
- [ ] **17.5** Remaining owner-run items are listed in [`release-mvp2.md`](../release-mvp2.md) → *Owner-run checklist*. Do those on the Oracle server **after** 09E

---

## X — Cross-cutting checks

**Responsive & browsers**
- [ ] **X.1** At 360 px wide: header **mobile menu** opens/closes, marketplace, product, checkout, library, dashboard, admin pages are usable without horizontal scrolling
- [ ] **X.2** The main journey (register → buy → read) works in **Chrome**, **Edge** and **Firefox** (and Safari/iPhone if available)

**Security probes**
- [ ] **X.3** Every product mutation (`unpublishProduct`, `deleteProduct`, `retryProcessing`, `republishProduct`, `retireDocumentVersion`) as **Creator B on Creator A's product** → `ACCESS_DENIED`
- [ ] **X.4** Any mutation in GraphiQL with **no** Authorization header → `UNAUTHENTICATED` (public marketplace queries still work)
- [ ] **X.5** Nowhere in the app does Network show a clean (unwatermarked) page image (check previews, reader, MinIO links)
- [ ] **X.6** Change an id in the URL to someone else's: `/orders/<other id>/receipt`, `/checkout/<other order>` → denied

**Resilience**
- [ ] **X.7** Stop the backend while the app is open → friendly error states, no white screen; start it → the app recovers
- [ ] **X.8** Restart the backend in the middle of an upload's processing → the job is picked up again and finishes (or fails cleanly)

**Polish**
- [ ] **X.9** No errors in the browser console during the main journey (outside the reader)
- [ ] **X.10** Every email in Mailpit (purchase, reset, payout, refund) has correct links pointing to `localhost:5173`, a readable subject, and no raw template placeholders

---

## Bug log

| Bug # | Check ID | What happened (steps → actual vs expected) | Severity (blocker / major / minor) | GitHub Issue | Status |
|---|---|---|---|---|---|
| B1 | | | | | |
| B2 | | | | | |
| B3 | | | | | |

To file one quickly:
```bash
gh issue create --label fix --label mvp2 --title "Fix: <short title> (check <ID>)" --body "<steps, actual, expected>"
```

---

## Sign-off

| Area | Result (✅ / ❌ / skipped) | Tester | Date |
|---|---|---|---|
| A. Automated suites | | | |
| Phases 1–4 (MVP1 core) | | | |
| Phases 5–8 (viewer, library, reviews, admin) | | | |
| Phase 9, 09B, 09C (hardening, payments, payouts) | | | |
| Phase 09D (prod stack rehearsal) | | | |
| Phases 10–13, 15–17 (MVP2) | | | |
| Cross-cutting | | | |

**Ready for 09E (Go live) when:** every blocker and major bug is fixed and retested, and
9D.3–9D.7 pass. Then follow [`docs/phases/phase-09e-go-live.md`](../phases/phase-09e-go-live.md).
