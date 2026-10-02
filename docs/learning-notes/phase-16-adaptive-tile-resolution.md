# Phase 16 — Adaptive tile resolution

> **Status:** Done (k6 comparison not run — see [`docs/perf/phase-16-adaptive-tiles.md`](../perf/phase-16-adaptive-tiles.md))
> **Built:** 2026-10-01
> **Requirement IDs covered:** MVP2-06 (spec: `docs/phases/phase-16-adaptive-tile-resolution.md`)
> **Commits:** see `git log --grep "Phase 16"`

---

## 1. What we built, in plain English

Every page of a document used to exist as one image, sized for a desktop monitor. A phone showing that page 390 pixels wide was downloading roughly three times more pixels than it could display, and the server spent CPU burning a watermark into all of them.

Now each page is rendered **twice** when a document is processed: a `DESKTOP` image (the old size) and a `MOBILE` image 900 pixels wide. When the reader opens a page, it works out how many real screen pixels the page will occupy and asks for the **smallest** image that still looks sharp. A phone on a normal screen gets `MOBILE`; a big monitor, or a high-density phone screen, gets `DESKTOP`. If the screen is resized or rotated, it asks again.

Documents uploaded before this phase only have `DESKTOP`. An admin can start a background job that renders the missing `MOBILE` images. While it runs, nothing breaks: if a `MOBILE` image doesn't exist yet, the server quietly sends the `DESKTOP` one.

**Before this phase:** one resolution per page for everyone.
**After this phase:** a list of resolutions (configurable), chosen per device, signed into the URL, with a safe fallback and a resumable backfill.

---

## 2. Why it matters

Bandwidth and CPU are the two scarce things on one small server. Watermark cost scales with pixel count, so a 900 px image costs about half the CPU of a 1240 px one, and it moves fewer bytes over a phone's connection. MVP2's goal is many more concurrent readers on the same hardware; mobile readers are the cheapest to serve *if* we stop over-serving them.

It is built now because Phase 13's cache key already had a `variant` field (it was `"default"`), and Phase 5's signed URL could be extended without changing the security model. Without the fallback rule, the backfill would have been a flag day: nobody could use `MOBILE` until every old document was converted.

---

## 3. New concepts introduced

### 3.1 Responsive images and `devicePixelRatio`

**What it is:** The browser's CSS pixel is not a screen pixel. `window.devicePixelRatio` (DPR) says how many physical pixels sit behind one CSS pixel: 1 on a typical monitor, 2 on a Retina laptop, 3 on many phones.

**The analogy:** Ordering a photo print. A 4-inch-wide frame needs about 1200 dots at 300 dpi. Sending 4000 dots wastes ink; sending 400 looks blurry. DPR is the "dots per inch" of the screen.

**Why we needed it here:** "Is the viewport small?" is the wrong question. A 390 px-wide phone at DPR 3 has 1170 physical pixels across and needs the big image; a 390 px window on a DPR-1 desktop does not.

**How it works:** `neededWidth = canvasCssWidth × devicePixelRatio`; pick the smallest variant whose width is ≥ `neededWidth`; if none is, use `DESKTOP`.

**In our code:** `frontend/src/lib/tileVariant.ts:14`
```ts
const neededWidth = Math.max(0, canvasCssWidth) * Math.max(1, devicePixelRatio || 1);
const sorted = [...variants].sort((a, b) => a.widthPx - b.widthPx);
const fits = sorted.find((variant) => variant.widthPx >= neededWidth);
```

**What breaks without it:** Phones at DPR 3 would be sent `MOBILE` (900 px for 1170 physical pixels): visibly soft text on a reading product. Or, ignoring DPR the other way, every phone gets `DESKTOP` and nothing is saved.

### 3.2 Rendering from a vector source vs resampling

**What it is:** A PDF stores text as shapes (outlines), not pixels. Rendering draws those shapes at whatever size you ask. *Resampling* means shrinking an existing bitmap.

**The analogy:** Redrawing a logo from the original artwork at a smaller size (crisp) vs. shrinking a screenshot of it (muddy).

**Why we needed it here:** D3 says each variant comes straight from the PDF page. A 900 px image drawn from the vector is hinted and anti-aliased for 900 px; a downscaled 1240 px image blurs thin strokes.

**In our code:** `PageTileRenderer.java:27`
```java
float scale = (variant.widthPx() + 0.01f) / visibleWidthPoints(pdf.getPage(pageIndex));
return renderer.renderImage(pageIndex, scale);
```
PDFBox sizes the image `floor(widthPts × scale)`; the `0.01` keeps floating-point error from producing 899 px when 900 was asked for (a risk designed out up front, not a bug we hit — see Gotchas).

**What breaks without it:** soft MOBILE text, and a pipeline that depends on the order variants are generated in.

### 3.3 Schema evolution with a composite unique key

**What it is:** The rule "one row per page" was a unique constraint on `(document_version_id, page_number)`. Two variants of the same page violate it, so the constraint must widen to include `variant`.

**How it works (V13):** add `variant NOT NULL DEFAULT 'DESKTOP'` (every existing row *was* desktop, so the default is truthful and needs no data rewrite), drop the old constraint, add `(document_version_id, page_number, variant)`. `VARCHAR(16)`, not an enum or `CHECK`, so adding `TABLET` is configuration, never a migration.

**In our code:** `backend/src/main/resources/db/migration/V13__adaptive_tile_variants.sql`

**What breaks without it:** the second insert for page 1 fails with a duplicate key error and the whole pipeline retries until FAILED. Every query that used `findByDocumentVersionIdAndPageNumber` would also silently return *either* row; that method was removed so the compiler forced every caller to say which variant it wants.

### 3.4 Graceful fallback during a backfill

**What it is:** The system is correct whether or not the new data exists yet. Missing `MOBILE` → serve `DESKTOP`.

**The analogy:** A shop that sells small and large coffee. If the small cups haven't arrived, you get the large one: you pay a bit more, you still get coffee.

**How it works:** the variant in the URL is verified first (so it is trustworthy), then the page row for that variant is looked up and, if absent, the `DESKTOP` row. The cache key and the access log use the variant **actually served**, not the one asked for.

**In our code:** `SecureTileService.java:144`
```java
.findByDocumentVersionIdAndPageNumberAndVariant(documentVersion.getId(), request.pageNumber(), requestedVariant)
.or(() -> contentPageRepository.findByDocumentVersionIdAndPageNumberAndVariant(
        documentVersion.getId(), request.pageNumber(), TileVariantProperties.DESKTOP))
```

**What breaks without it:** a migration window of errors (404s on every old document) or a flag-day rollout where the backfill must finish before any client may use `MOBILE`.

### 3.5 Signing the variant into the URL

**What it is:** The HMAC from Phase 5 now covers `sessionId|page|userId|exp|variant`. The client can't swap `MOBILE` for `DESKTOP` (or the reverse) by editing `&v=`.

**Why it matters here:** it isn't a secret — the buyer is entitled to both. It matters because the variant picks which *cache entry* and which *storage object* is read, and an unsigned parameter is an unvalidated input into both. Dropping `v` is treated as `DESKTOP` and fails verification for a MOBILE signature.

**In our code:** `TileUrlSigner.java:80`

### 3.6 A resumable backfill whose checkpoint is the data

**What it is:** `generateMissingVariants` finds work with a query — "processed, live versions that have fewer pages of this variant than `page_count`" — instead of tracking progress in a separate table.

**Why it's better:** a crash, a restart, or a second click can't leave a stale or contradictory progress record, because there isn't one. It is idempotent by construction, and it resumes at *page* level (each already-rendered page is skipped). Each version commits on its own, so a stop loses at most one version of work. The same idea as Phase 15's entitlement migration.

**In our code:** `ContentPageRepository.java:28` (the query), `VariantBackfillService.java:81` (the loop)

**Low priority:** it runs on the processing thread pool (never a request or render thread) at `Thread.MIN_PRIORITY` with a pause between versions. Thread priority is only a hint on Linux, so the pause is the part that actually protects uploads.

### 3.7 Watermark scaling

**What it is:** the label's font scales with image width: `fontSize = base × width / referenceWidth`, minimum 12. A fixed 24 px label on a 900 px image looks a third bigger (relative to the page) than on a 1240 px one and covers more text.

**In our code:** `Java2DWatermarkRenderer.java:54`; contract test `Java2DWatermarkRendererTest` measures the fraction of non-white pixels on a blank page: 1.41 % at 900 px vs 1.37 % at 1240 px.

---

## 4. Best practices applied

| Practice | What we did | Why it matters | Where |
|---|---|---|---|
| Config over code | Variants are a list in `application.yml`; names are free text | A `TABLET` variant is a config change | `TileVariantProperties.java`, `application.yml` |
| Authenticate every input that selects data | Variant is inside the HMAC | A query parameter picking a storage object must not be forgeable | `TileUrlSigner.java:80` |
| Derive checkpoints from data | Backfill finds work by query | Nothing to corrupt; idempotent | `ContentPageRepository.java:28` |
| One transaction per unit of work | `VariantVersionBackfiller` is its own bean | `@Transactional` needs the Spring proxy; a stop loses one version | `VariantVersionBackfiller.java` |
| Log what happened, not what was asked | Access log + cache key use the served variant | Analytics and cache correctness | `SecureTileService.java:151` |
| Make the compiler find every caller | Removed the 2-arg page lookup | No query silently returns "one of" two rows | `ContentPageRepository.java` |
| Test the contract, not the pixels | Coverage band + ratio between variants | Resilient to font changes, fails if the mark vanishes or shouts | `Java2DWatermarkRendererTest` |
| Pure function for the decision | `chooseTileVariant` has no DOM | Trivially unit-testable | `tileVariant.ts` |

---

## 5. What does what — file map

| File | Responsibility |
|---|---|
| `V13__adaptive_tile_variants.sql` | `content_pages.variant`, widened unique key, `viewer_access_logs.variant` |
| `content/tiles/TileVariantProperties.java` | The configured variants (name, width, optional DPI); `resolve()` falls back to DESKTOP |
| `content/tiles/PageTileRenderer.java` | Renders one page at one variant from the PDF |
| `content/service/DocumentProcessingService.java` | `CONVERT_TILES` loops variants; links saved once, on the DESKTOP row |
| `content/tiles/VariantBackfillService.java` | Admin-triggered background run; one at a time |
| `content/tiles/VariantVersionBackfiller.java` | Renders the missing pages of one version, in its own transaction |
| `admin/resolver/AdminTileVariantResolver.java` | `generateMissingVariants` mutation, `hasRole('ADMIN')` |
| `viewer/security/TileUrlSigner.java` | Signs/verifies the variant |
| `viewer/service/SecureTileService.java` | Verifies, looks up variant row, falls back, keys cache, logs |
| `viewer/service/ViewerSessionService.java` | Mints the URL with `&v=`; returns `tileVariants` with the session |
| `content/watermark/Java2DWatermarkRenderer.java` | Font scales with image width |
| `frontend/src/lib/tileVariant.ts` | `chooseTileVariant` |
| `frontend/src/hooks/viewer/useTileVariant.ts` | Evaluates on mount and (debounced) on resize / rotation |
| `frontend/src/hooks/viewer/useSecureTile.ts` | Requests the variant; drops prefetched pages of another variant |

**Request trace — one MOBILE page:**
1. `useTileVariant` computes `390 × 1 = 390 ≤ 900` → `"MOBILE"` →
2. `viewerPageUrl(sessionToken, pageNumber, variant: "MOBILE")` → `ViewerSessionService.signPageUrl` signs it and returns `/api/viewer/tiles/…?exp=…&sig=…&v=MOBILE` →
3. `GET` with the JWT → `SecureTileService.prepareTile` verifies the signature (variant included), single-use check, session, entitlement, page range →
4. looks up the MOBILE page row (or DESKTOP if absent); builds the cache key with the *served* variant; cache hit returns, miss fetches the clean 900 px PNG →
5. render pool watermarks it (font scaled to 900 px) → access log row with `variant` → bytes to the browser.

---

## 6. Design decisions and trade-offs

### Decision: render every variant at upload time (and via backfill), not on demand
- **Alternatives considered:** render MOBILE lazily on first request and store it; downscale on the fly per request.
- **Why we chose this:** the first mobile reader of each page would pay a PDF render (hundreds of ms, CPU-heavy) *inside* a request; the pipeline already has the PDF open and runs off the request path.
- **What we gave up:** storage (+~70 % per page, see the perf doc) and ~2× the CONVERT_TILES time, for variants that may never be read.
- **When we would revisit:** if the tiles bucket grows too large, make rarely used variants lazy.

### Decision: render from the PDF, not downscale the desktop PNG
- **Alternatives considered:** `Graphics2D.drawImage` the desktop PNG to 900 px (cheaper: no PDF re-parse).
- **Why we chose this:** sharper text; the spec's D3. It also avoids a hidden dependency between variants.
- **What we gave up:** PDF rendering time per variant.

### Decision: variant names are strings, not a Java/GraphQL enum or a DB enum
- **Alternatives considered:** `enum TileVariant { DESKTOP, MOBILE }` everywhere.
- **Why we chose this:** spec D1 wants a `TABLET` variant to need no code change; an enum would force a schema, GraphQL and migration edit.
- **What we gave up:** compile-time exhaustiveness; an unknown name from a client must be handled (we treat it as `DESKTOP`, and an unsigned/unknown `v` in a tile URL is a 403).
- **When we would revisit:** if variants stop being config.

### Decision: the client sends the variant name; the server doesn't guess from `User-Agent`
- **Alternatives considered:** server-side device detection; `srcset`/`<picture>`.
- **Why we chose this:** only the client knows its canvas width and DPR *after* zoom/layout. `<img srcset>` is not an option because pages are drawn on a canvas (Phase 5, so no clean image is ever in the DOM).
- **What we gave up:** an extra field on `viewerPageUrl`.

### Decision: links are stored once, on the DESKTOP row
- **Why:** they're ratios of the page (0..1), so they fit every variant. Storing them per variant would triplicate rows for no benefit. `findForPage` filters on `DESKTOP`.

### Decision: bump `tilecache.watermark-version` to `"2"`
- **Why:** the watermark font now depends on width, so any DESKTOP tile cached before this change was drawn with a different font. Changing the version orphans them (Phase 13's invalidation by key change).

---

## 7. Interview questions

### Beginner
**Q: What is `devicePixelRatio` and why does it matter for images?**
A: It's the number of physical screen pixels per CSS pixel — 1 on a normal monitor, 2 on Retina, 3 on many phones. If a page is 390 CSS pixels wide on a DPR-3 phone, it has 1170 real pixels to fill, so sending a 900-pixel image makes it blurry. You multiply the CSS width by DPR to know how many image pixels you actually need.

**Q: Why store two sizes of each page instead of resizing on request?**
A: Resizing costs CPU on every request, and rendering from the PDF is the expensive part. Doing it once at upload moves the cost out of the request path. The price is extra storage.

**Q: What happens if the mobile image doesn't exist yet?**
A: The server serves the desktop one. The reader works either way — it just downloads more.

### Intermediate
**Q: Why is the variant part of the signed URL?**
A: The signature already binds session, page, user and expiry. The variant selects a storage object and a cache entry, so it's an input the client controls. If it weren't signed a user could request any variant they like on someone else's single-use URL, and tampering wouldn't be detected. Signed, a swapped or removed `v` fails verification and gets a 403.

**Q: You changed a unique constraint on a live table. How did you do it safely?**
A: Add the column with a default that is true for every existing row (`'DESKTOP'`), so there is no data rewrite; then in the same migration drop the old constraint and add the wider one. Existing rows are unique under the new key because they all share one variant. I used `VARCHAR` rather than a Postgres enum because adding a value to an enum is its own migration, and I want to add variants in config.

**Q: Why scale the watermark font by image width?**
A: Otherwise the same 24 px text takes up a bigger share of a smaller image — it hides more of the page and looks heavier. Scaling by `width / referenceWidth` keeps the mark proportionally the same, and a minimum of 12 px keeps it legible on tiny images. I tested it as a contract: ink coverage on a blank page must sit in a band and be about the same at 900 and 1240 pixels.

**Q: How is the backfill resumable without a progress table?**
A: The work list is a query over the data: versions with fewer pages of the variant than their page count. A stopped run just finds what's left next time. Within a version I skip pages that already have a row. Each version commits separately, so I lose at most one version of work on a crash.

### Advanced / follow-up probes
**Q: Two admins click "generate" at once. What happens?**
A: An `AtomicBoolean` makes the second call a no-op, so one JVM never runs two. Across several instances it wouldn't hold — but the work is idempotent, and the unique key `(version, page, variant)` makes a duplicate insert fail rather than duplicate data (that version's transaction would roll back and be picked up next run). If we scaled out I'd use a Postgres advisory lock.

**Q: A cache key must contain the variant. Which one — requested or served?**
A: Served. If I keyed on "requested MOBILE" but served DESKTOP bytes during the backfill, the cache would keep returning DESKTOP bytes under the MOBILE key after the backfill finished (until TTL). Keying on what was served means each key always maps to the bytes of that variant.

**Q: Why not use `<img srcset>` — it's literally built for this?**
A: Because the page is drawn into a canvas from a fetch with an `Authorization` header, so no image URL is ever in the DOM (Phase 5's anti-extraction design). The browser's selection logic isn't available, so we replicate it in a small pure function.

**Q: What would you change to cut bytes further?**
A: Lossy WebP or AVIF. It would shrink files much more than a smaller PNG, but AVIF encoding is CPU-heavy and old browsers lack it, so it needs the same measurement discipline: render cost vs bytes vs support.

### "Tell me about a bug you fixed"
**Q: Tell me about a bug you hit in this phase.**
A: After adding variants, the full suite failed in two places that had nothing to do with the code I'd just written: a pipeline test expected two page rows and a load-test seeder test expected twelve. The cause was my own change — one row per page *per variant* — so every "how many rows" assertion was now half the truth. I fixed them to count per variant, which also made them test the new behaviour (both variants exist). What I took from it: widening a key changes the meaning of every count over that table, so grep for those assertions up front. A related risk I designed out rather than hit: PDFBox floors `widthPts × scale`, so a naive scale could give 899 px instead of 900; I added an epsilon and asserted the exact width.

---

## 8. Gotchas and bugs we hit

| Symptom | Root cause | Fix | Lesson |
|---|---|---|---|
| Existing tests asserted `hasSize(2)` pages and `count(content_pages) = 12` and failed | The pipeline now stores one row per variant (2 × pages) | Tests count per variant | A widened key changes every "how many rows" assertion — update them deliberately |
| `RenderBackpressureIT` stopped compiling | `ViewerAccessLogService.record` gained a `variant` argument; the test stubs it with matchers | Added one `Mockito.any()` | Mockito argument matchers pin arity |
| (Avoided, not hit) width could be 899 instead of 900 | PDFBox floors `widthPts × scale` | `+0.01f` in the scale, exact-width assertion in `AdaptiveTileVariantsIT` | Library rounding is part of your contract |
| (Avoided) a prefetched page of the old variant could be drawn after rotating | The prefetch cache was keyed by page number only | Cache cleared when the variant changes | Cache keys must contain everything that changes the bytes (same lesson as Phase 13) |
| (Avoided) links would have been duplicated per variant, and a plain `findForPage` would join both rows | Links hang off `content_pages` | Save links only on the DESKTOP row; filter in `findForPage` | When you multiply rows, check every child table's join |

---

## 9. New vocabulary

| Term | One-line meaning |
|---|---|
| **Tile variant** | One resolution of a page image (`DESKTOP`, `MOBILE`, …), a row per page per variant |
| **devicePixelRatio (DPR)** | Physical screen pixels per CSS pixel |
| **Composite unique key** | A uniqueness rule over several columns together |
| **Vector vs raster** | Shapes you can redraw at any size vs a fixed grid of pixels |
| **Graceful fallback** | Serving a slightly worse answer instead of an error when the ideal one is missing |
| **Debounce** | Waiting for events (resize) to stop before acting |

---

## 10. If I had to defend this in a code review

- **Strongest point:** the fallback + signed variant make the rollout safe at every step — new code before the backfill, backfill running, or never backfilled all work, and the variant can't be forged.
- **Strongest point:** the backfill's checkpoint is the data itself, so it can't get out of sync with reality.
- **Weakest point:** the "one run at a time" guard is per JVM. With several backend instances I'd use a Postgres advisory lock. Also the perf claim isn't proven yet: I measured stored sizes (MOBILE ≈ 70 % of DESKTOP bytes) but did not run the k6 comparison; the doc has the commands and an empty table.
- **Second weakest:** PNG for both variants. Lossy WebP would save more than a smaller PNG; I left it out of scope deliberately.
