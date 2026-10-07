# UI 02 — Creator dashboard "Actions" column + watermark-free owner preview

> **Status:** Done
> **Built:** 2026-10-03
> **Requirement IDs covered:** DASH-01 (product list, now with a labelled Actions column). The owner preview is a new creator feature with no requirement ID yet; see the VIEW-03 conflict in §6.
> **Commits:** _(see the "Creator owner preview" PR)_

---

## 1. What we built, in plain English

Two small changes to the creator dashboard.

1. The last column of the products table had **no heading**. The Unpublish / Versions / Delete buttons sat under a blank header. It is now labelled **Actions**, centred, with the buttons centred beneath it.
2. A creator can now **click a product in the table and read it**, exactly as they uploaded it, **with no watermark**. A new full-screen page opens at `/creator/products/:id/view` with Prev / Next buttons and arrow-key navigation, plus a small "Owner preview · no watermark" badge so nobody mistakes it for the buyer view.

Before this, a creator had no way to see their own product after upload. They couldn't own an entitlement to it, so the buyer reader turned them away. And if they bought it with a second account, every page was watermarked with that account's email.

**Before:** blank column header; the creator could not read their own product.
**After:** "Actions" header; one click opens a clean owner preview of any processed product.

---

## 2. Why it matters

A creator needs to **check what they are selling**: page order, rendering quality, whether the right PDF version is live. Making them buy their own book to do that, and then see it stamped with their own email, is broken UX.

The interesting part is that this feature **deliberately creates a hole in the DRM** — a path that serves *clean* pages. The DRM's whole promise is "the browser never receives a clean tile" (VIEW-03). So the real engineering question is **how to add that hole without weakening the buyer path**. That's what this note is mostly about.

---

## 3. New concepts introduced

### 3.1 Why a watermark is pointless for the owner (threat modelling a feature)

**What it is:** Before adding a security exception, ask: *what attack does the control stop, and does that attack exist for this user?*

**The analogy:** A hotel prints your name on the towels so a stolen one can be traced back to you. It makes no sense to print the hotel owner's name on towels in their own house.

**Why we needed it here:** The watermark exists to **trace a leak back to the buyer** who leaked it (Phase 5). The creator **uploaded the original PDF**. They already have a perfect clean copy on their own disk. Watermarking their view protects nothing and only annoys them.

**What still matters:** the threat is no longer "the creator leaks it". It's **"someone who is NOT the creator gets clean pages"**. So all the effort goes into *who* can call this endpoint, not *what* the creator does with the result. That's §3.2 and §3.3.

### 3.2 Isolating a security exception (don't put a bypass in the hot path)

**What it is:** When a rule needs an exception, put the exception in **its own small, separately-tested code path** instead of adding an `if (exception)` branch inside the code that enforces the rule.

**The analogy:** A bank doesn't give the main vault door a "manager override" button. The manager has a separate, smaller safe with its own key and its own log.

**Why we needed it here:** The obvious implementation is one line in `SecureTileService`: `if (session.user is creator) return cleanBytes;`. But that class's documented invariant is *"every byte this class returns has gone through WatermarkRenderer. There is no path here that returns clean storage bytes."* (`backend/src/main/java/com/secureleaf/viewer/service/SecureTileService.java:36-39`). Adding a bypass there means **every buyer request** now runs through code that *can* return clean bytes. One wrong condition, one refactor that confuses user ids, and buyers get clean pages.

Instead, the owner preview is a **separate controller + service** that never touches the buyer pipeline:

**In our code:** `backend/src/main/java/com/secureleaf/creator/service/CreatorPageService.java:39-54`
```java
Product product = productRepository.findByIdAndDeletedAtIsNull(productId)
        .filter(p -> p.getCreator().getId().equals(creatorId))
        .orElseThrow(() -> new ResourceNotFoundException("Product", productId));
...
if (pageCount == null || pageNumber < 1 || pageNumber > pageCount) {
    throw new ResourceNotFoundException("CreatorPage", "pageNumber", pageNumber);
}
...
return storageService.get(page.getBucketName(), page.getMinioObjectKey());
```

**What breaks without it:** the buyer pipeline's "never clean" guarantee becomes "never clean, unless this condition is right", and *that* is far harder to audit or prove with a test.

### 3.3 Two layers of authorization: role at the edge, ownership in the service

**What it is:**
- **RBAC (role-based access control)** answers "is this user *a* creator?". It's checked once, in the HTTP filter chain, for a whole URL prefix.
- **Object-level authorization** answers "is this user *the* creator *of this product*?". It can only be checked once you've loaded the product.

**In our code:**
- Role: `backend/src/main/java/com/secureleaf/auth/security/SecurityConfig.java:103`: `GET /api/creator/**` → `hasRole("CREATOR")`. This was already there for the statements CSV; the new endpoint inherits it for free by living under `/api/creator/`.
- Ownership: the `.filter(p -> p.getCreator().getId().equals(creatorId))` above. `creatorId` comes from the **JWT**, never from the URL (`CreatorPageController.java:30-33`, `@AuthenticationPrincipal`).

**What breaks without the second layer:** **BOLA** (Broken Object Level Authorization, OWASP API #1). Any creator could change the product id in the URL and read *every other creator's* book, clean. The role check alone would happily let them through, because they *are* a creator. `CreatorPageIT.rivalCreator_gets404_neverTheCleanPage` is the test that pins this down.

### 3.4 404, not 403, for "not yours"

**What it is:** When a creator asks for someone else's product, we answer **404 Not Found**, not **403 Forbidden**.

**Why:** a 403 says "this exists, but you can't have it". That lets an attacker **enumerate** which product ids exist. A 404 for "doesn't exist", "deleted" *and* "not yours" makes all three look identical. Same rule as `PreviewService` in Phase 3.

### 3.5 Object URLs for authenticated images (frontend)

**What it is:** `URL.createObjectURL(blob)` turns bytes you already downloaded into a temporary `blob:` URL that an `<img>` can show.

**Why we needed it here:** the endpoint needs an `Authorization: Bearer …` header. A plain `<img src="/api/…">` **can't send headers**, so it would get a 401. Instead we fetch with `restClient` (which attaches the token and handles a token refresh on 401), then hand the blob to the `<img>`.

**In our code:** `frontend/src/hooks/useCreatorPage.ts:38` fetches with `responseType: 'blob'`; `:55` calls `URL.revokeObjectURL` in the effect cleanup.

**What breaks without the cleanup:** every page turn creates a new object URL that holds the image in memory until the tab closes. Read a 300-page book and you leak 300 images. Revoking in the `useEffect` cleanup frees the old page the moment the page number changes or the component unmounts.

---

## 4. Best practices applied

| Practice | What we did | Why it matters | Where |
|---|---|---|---|
| Isolate security exceptions | Separate `CreatorPageService` instead of a branch in `SecureTileService` | Buyer path keeps its "never clean" invariant untouched | `creator/service/CreatorPageService.java` |
| Defense in depth | Role check in `SecurityConfig` **and** ownership check in the service | Either layer alone has a known hole (wrong role / BOLA) | `SecurityConfig.java:103`, `CreatorPageService.java:40` |
| Identity from the token, not the URL | `@AuthenticationPrincipal` supplies `creatorId` | The caller can't claim to be someone else | `CreatorPageController.java:31-33` |
| No existence leak | Every failure is 404 | Can't probe which product ids exist | `CreatorPageService.java:41,48,53` |
| Rate-limit the valuable thing | Path shares the buyer `tile:{userId}` bucket | A stolen creator login can't scrape clean pages faster than a buyer reads watermarked ones | `RateLimitFilter.java:42,54,63` |
| Never cache clean pages | `Cache-Control: no-store, private` | A shared proxy or the browser's disk cache would otherwise hold a clean copy | `CreatorPageController.java:37` |
| REST for files, GraphQL for data | Page bytes over REST; title/page count from the cached `MY_PRODUCTS` GraphQL query | Project convention (CLAUDE.md); no extra round trip for metadata | `CreatorPreviewPage.tsx:27` |
| Test the boundary, not the happy path only | IT covers owner, rival creator, buyer, anonymous, out-of-range, soft-deleted | The rival-creator case is the one that leaks content if wrong | `backend/src/test/java/com/secureleaf/creator/CreatorPageIT.java` |
| Accessible headers and links | Real `Actions` `<th>`; product cell is a `<Link>` with an `aria-label` and focus ring | Screen readers announce the column; keyboard users can open the preview | `CreatorDashboardPage.tsx:119-129,376` |

---

## 5. What does what — file map

| File | Responsibility |
|---|---|
| `backend/.../creator/controller/CreatorPageController.java` | `GET /api/creator/products/{id}/pages/{n}` → PNG, no-store headers |
| `backend/.../creator/service/CreatorPageService.java` | Ownership check, page-range check, clean bytes from storage |
| `backend/.../ratelimit/RateLimitFilter.java` | Puts the new path in the per-user `tile` bucket |
| `backend/.../auth/security/SecurityConfig.java` | (unchanged) `/api/creator/**` is CREATOR-only |
| `frontend/src/hooks/useCreatorPage.ts` | Fetch one page as a blob, expose an object URL, revoke it on change |
| `frontend/src/pages/creator/CreatorPreviewPage.tsx` | Full-screen viewer: title, badge, Prev/Next, arrow keys, `?page=` in the URL |
| `frontend/src/pages/creator/CreatorDashboardPage.tsx` | "Actions" header; product cell links to the preview when `pageCount > 0` |
| `frontend/src/App.tsx` | Route `/creator/products/:id/view` behind `ProtectedRoute requiredRole="CREATOR"` |

**Request trace — creator clicks "My Guide" in the dashboard:**
1. `CreatorDashboardPage` `<Link to="/creator/products/7/view">` →
2. `CreatorPreviewPage` reads title + `pageCount` from the Apollo cache (`MY_PRODUCTS`) →
3. `useCreatorPage("7", 1)` → `restClient.get('/creator/products/7/pages/1', { responseType: 'blob' })` with the Bearer token →
4. `JwtAuthenticationFilter` → `RateLimitFilter` (bucket `tile:{userId}`) → `SecurityConfig` (`hasRole CREATOR`) →
5. `CreatorPageController.page` → `CreatorPageService.getOwnPage(creatorIdFromJwt, 7, 1)` → ownership + range checks → `StorageService.get` →
6. PNG bytes, `no-store` → blob → `URL.createObjectURL` → `<img>`.

---

## 6. Design decisions and trade-offs

### Decision: a separate owner endpoint, not an "is owner" branch in the buyer tile pipeline
- **Alternatives considered:**
  1. Let the creator start a normal viewer session and skip the watermark when `session.user == product.creator`.
  2. Grant the creator a free entitlement on upload so the existing reader works (with a watermark).
- **Why we chose this:** (1) puts a watermark bypass inside the class whose job is "never clean" (§3.2). It also drags in sessions, heartbeats, signed URLs and the access-log table, none of which protect anything for the owner. (2) still watermarks, which is what the user asked us to remove, and it would also pollute sales and library data with fake entitlements.
- **What we gave up:** the owner preview has none of the buyer reader's extras: no zoom controls, no prefetch of the next page, no clickable in-PDF links, no tile cache, no MOBILE variant (always DESKTOP). It's a checking tool, not a reading experience.
- **When we would revisit:** if creators start reading long documents here and complain about speed, add a one-page prefetch to `useCreatorPage` (the buyer hook's pattern), not a merge into the DRM pipeline.

### Decision: the creator sees the **current** version only
- **Alternatives considered:** a `?version=` parameter so the Versions page can preview any version.
- **Why:** matches "what a new buyer gets right now", and keeps the endpoint small. The current-version pointer is the Phase 15 source of truth.
- **What we gave up:** you can't preview a v2 that is still processing or a retired v1.
- **When we would revisit:** if the Versions page needs a "preview before making current" flow.

### Decision: share the buyer `tile` rate-limit bucket
- **Alternatives considered:** no limit (it's the owner), or a new `creator-page` bucket with its own config.
- **Why:** a stolen creator password is the realistic threat, and clean pages are the most valuable thing it unlocks. The buyer limit (2 req/s sustained) is already tuned for "a human reading", which is exactly what an owner does. Reusing it needs no new config.
- **What we gave up:** a creator reading in the owner preview and the buyer reader *at the same time* shares one budget. Unlikely, and the cost is only a "too fast" message.

### ⚠️ Documentation conflict (flagged, not silently resolved)
`docs/requirements.md` VIEW-03 and the pitch in `interview-prep/quick-reference.md` both say **"The browser never receives a clean tile."** That is no longer literally true: the *owner's* browser now receives clean pages of *their own* product. The buyer statement is still true. The owner should decide whether to reword VIEW-03 (e.g. "…never receives a clean tile of a product they don't own") and give this feature a requirement id. This note doesn't change `requirements.md`.

---

## 7. Interview questions

### Beginner
**Q: Why does the creator get the page without a watermark when buyers don't?**
A: The watermark is there so a leaked page can be traced back to the buyer who leaked it. The creator uploaded the original PDF, so they already have a clean copy. Watermarking their view wouldn't protect anything.

**Q: Why fetch the image with JavaScript instead of just `<img src="/api/...">`?**
A: The endpoint needs the JWT in an `Authorization` header, and an `<img>` tag can't send headers. So I fetch it with our axios client, which adds the token, turn the bytes into an object URL, and give that to the `<img>`.

**Q: What does the "Actions" column change actually fix?**
A: The buttons had a blank header, which hurts screen-reader users: the column has no name to announce. And sighted users have to guess what the column is. Now it's a real `<th>` labelled "Actions", centred over the centred buttons.

### Intermediate
**Q: What stops one creator from reading another creator's book this way?**
A: Two checks. The security config only lets users with the CREATOR role into `/api/creator/**`. Then the service loads the product and checks that its creator id equals the id from the caller's JWT. If not, it's a 404. The role check alone wouldn't be enough, because the attacker *is* a creator. That's the classic BOLA bug, and there's an integration test for exactly that case.

**Q: Why 404 instead of 403 for someone else's product?**
A: A 403 tells the caller "this exists, you just can't see it", so they can probe which ids exist. Returning 404 for "missing", "deleted" and "not yours" makes all three indistinguishable.

**Q: You revoke the object URL in a `useEffect` cleanup. What happens if you don't?**
A: Each object URL keeps its blob alive until the page unloads. Every page turn would leak one full-size page image, so a long book would slowly eat memory. The cleanup runs whenever the page number changes or the component unmounts, so only the current page is held.

### Advanced / follow-up probes
**Q: Why not just add `if (isOwner) skip watermark` to the existing tile service? It's one line.**
A: Because of where the line lives. That service is the path every buyer request goes through, and its whole guarantee is "nothing clean ever leaves here". With a bypass inside it, that guarantee depends on the condition being right forever, through every refactor. If someone mixes up the session user and the product creator, buyers get clean pages. A separate endpoint keeps the buyer path provably clean-free, and keeps the exception small enough to test exhaustively.

**Q: The creator owns the content. Why rate-limit it at all?**
A: The threat isn't the creator, it's someone with the creator's *password*. A stolen creator login is now the one credential that yields clean pages, so it gets the same "human reading speed" budget as the buyer tile endpoint. A scraper can't dump a whole catalogue in seconds.

**Q: Why `Cache-Control: no-store` if the user is allowed to see it?**
A: "Allowed to see it" isn't "allowed to keep a copy in every cache along the way". `no-store` keeps it out of the browser's disk cache, so it isn't left on a shared machine after logout. `private` keeps it out of any shared proxy. That matches what the buyer tile endpoint does.

**Q: What's the weakest part of this design?**
A: It now *is* possible to get clean pages out of the system, and the only protection is the creator's login. The creator account has become the highest-value credential on the platform. The next fix I'd make is to require a recent re-authentication (or 2FA) for the owner preview, the way banks re-prompt before showing card details.

### "Tell me about a bug you fixed"
**Q: Tell me about a feature that passed its tests but didn't work.**
A: I added a clickable product cell to the creator dashboard. It only shows the link when the product has pages, and the tests were green. The user clicked it and nothing happened. The dashboard's `myProducts` query had always returned `pageCount` as null, because the mapper was called without it, so the link never rendered. My component test passed because I'd written the mock with `pageCount: 10`, and that mock was wrong about the server. I fixed the resolver, fetched the version in the same query to avoid an N+1, and added an integration test that reads `pageCount` through the real GraphQL API. The lesson: a mock encodes an assumption about the contract, so at least one test has to check that contract for real.

**Q: Tell me about a test that failed while you were building this.**
A: I changed the rate-limit filter, ran the rate-limit integration tests, and two preview-limit tests failed with 404 where they expected 429. Before touching anything, I stashed my change and re-ran: it still failed, once with one test and once with two. So it was timing, not me. The preview bucket holds 20 tokens and refills one per second. The test makes 20 requests and expects the 21st to be rejected, but on a slow laptop those 20 requests take several seconds, so a token refills first. The lesson: when a test fails right after your change, prove causation by reverting before you start "fixing". And a test that depends on wall-clock speed should pin the clock or the refill rate.

---

## 8. Gotchas and bugs we hit

| Symptom | Root cause | Fix | Lesson |
|---|---|---|---|
| **Clicking a product in the dashboard did nothing** — reported by the user after the feature "passed" its tests | `ProductService.myProducts` mapped with `ProductMapper::toDto`, which always sets `pageCount = null`. The dashboard only links when `pageCount > 0`, so no product was ever clickable. The frontend test's mock data had `pageCount: 10`, a value the real server never sent | `myProducts` now passes the current version's `pageCount`; `currentDocumentVersion` added to the `@EntityGraph` so it's one JOIN, not N+1 queries. Regression test `CreatorPageIT.myProducts_returnsTheCurrentVersionsPageCount…` goes through the real GraphQL query | **A mock is a claim about the server, and nobody checks it.** When the UI depends on a field, have one test that gets that field from the real API. Green unit tests + a mocked contract ≠ a working feature |
| `RateLimitIT` preview tests fail (404 instead of 429), 1 or 2 of them, varying run to run | **Pre-existing, timing-dependent:** bucket capacity 20, refill 1/s; the test's 20 warm-up requests take 2–9 s on a slow machine, so ≥1 token refills before request 21 | Not fixed here (out of scope). Verified it fails identically with this change reverted. Proper fix: give the test profile `ratelimit.preview.refill-per-second: 0`, or inject a fixed `Clock` | **Revert first, then diagnose.** A failure right after your change isn't proof your change caused it |
| _(design trap avoided)_ The tempting one-line `if owner → skip watermark` in `SecureTileService` | That class's invariant is "no clean bytes, ever"; a bypass there puts every buyer request one bad condition away from a leak | Separate controller + service under `/api/creator/` | **Learning moment:** put security exceptions in their own small path, never inside the code that enforces the rule |
| _(avoided)_ Clickable product cell for a DRAFT/PROCESSING product would open a page that can only 404 | No processed document → no pages | Only link when `pageCount > 0` | Don't render an affordance that can't work |

---

## 9. New vocabulary

| Term | One-line meaning |
|---|---|
| **Owner preview** | The creator viewing their own product's pages, clean, via an owner-only endpoint |
| **Object URL** | A temporary `blob:` URL pointing at bytes already in memory; must be revoked to free them |
| **Security exception isolation** | Putting a rule's exception in its own small code path instead of a branch inside the rule's enforcer |

(BOLA, RBAC and token bucket are already in the glossary from Phases 1, 8 and 11.)

---

## 10. If I had to defend this in a code review

- **Strongest point:** the buyer DRM pipeline is untouched. Its "never clean" invariant still holds, still enforced by the same code and tests. The exception lives in ~20 lines behind two independent authorization checks.
- **Also strong:** the rival-creator BOLA case is an integration test, not a hope, and every failure looks like a 404.
- **Honest limitation:** a deliberately minimal viewer. No zoom, no prefetch, no in-PDF links, DESKTOP resolution only, current version only.
- **Weakest point, fix first:** clean pages are now one password away. Add step-up authentication (recent login or 2FA) before the owner preview, and log owner-preview reads so an admin can spot a hijacked creator account scraping.
