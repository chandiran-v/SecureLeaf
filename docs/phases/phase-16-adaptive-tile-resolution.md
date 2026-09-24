# Phase 16 — Adaptive tile resolution

> Spec for an unattended run. Decisions are numbered (D1…) and referenced from code comments.
> Requirements: **MVP2-06**.
> Depends on: Phase 15. Target branch: `feature/secure-leaf-mvp2`.

## Context

Every page exists at one desktop resolution. On phones that wastes bandwidth, and it wastes watermark CPU, since burn cost scales with pixel count. This phase renders multiple variants per page and serves the smallest one that still looks sharp.

## Decisions

- **D1 — Variants.**
  - `DESKTOP`: the current width (keep the existing value; read it from the pipeline config).
  - `MOBILE`: 900 px wide.

  Both keep the aspect ratio. This is configurable as a list, so a `TABLET` variant could be added later without code changes.
- **D2 — Schema.** A migration adds `content_pages.variant VARCHAR(16) NOT NULL DEFAULT 'DESKTOP'`, and replaces `uq_content_pages_version_page` with a unique `(document_version_id, page_number, variant)`. Existing rows become DESKTOP.
- **D3 — Pipeline.** The `CONVERT_TILES` stage renders every variant from the PDF page directly with PDFBox at the right DPI. **Don't** downscale the desktop PNG: rendering from the vector source is sharper. Storage keys include the variant.
- **D4 — Backfill.** An admin-triggered, resumable job, `generateMissingVariants`, renders MOBILE for all existing versions in the background using the processing thread pool, at low priority. The viewer falls back to DESKTOP when a variant is missing, so nothing breaks while it runs.
- **D5 — Choosing the variant (client).** The viewer computes `neededWidth = canvasCssWidth × devicePixelRatio` and asks for the smallest variant at least that wide (DESKTOP if none is). It re-evaluates on resize and orientation change, debounced.
  - `viewerPageUrl(…, variant: TileVariant)`.
  - The signature from 05A (D5 there) **includes the variant**.
  - The Phase 13 cache key already has `variant`.
- **D6 — Watermark scaling.** Font size scales with image width, so it has the same visual weight on both variants: `fontSize = base × width / desktopWidth`, with a minimum of 12 px. Update both renderers and extend the contract test.
- **D7 — Access log.** Record the variant in a new column `viewer_access_logs.variant`, for analytics.

## Acceptance criteria
1. A new upload produces both variants for every page, with the expected widths.
2. Asking for MOBILE returns the smaller image, correctly watermarked, and a tampered variant in the URL gets 403.
3. A missing MOBILE variant falls back to DESKTOP, with no error.
4. The backfill job renders missing variants, is idempotent and resumable, and is admin-only.
5. The watermark stays legible at 900 px (contract test: the watermark region's pixel coverage is within a range).
6. Frontend: with a small viewport and DPR 1, the viewer requests MOBILE. With a large viewport and DPR 2, it requests DESKTOP. Resize re-evaluates (unit test of the chooser).
7. The k6 mobile scenario shows lower bytes per tile and render time vs desktop, recorded in `docs/perf/`.

## Out of scope
- WebP or AVIF encoding (a good follow-up: note it with its browser-support and CPU trade-off).
- Deep zoom tiling (splitting a page into sub-tiles).

## Learning note
Create `docs/learning-notes/phase-16-adaptive-tile-resolution.md`. Headline topics:
- responsive images and devicePixelRatio
- rendering from vector sources vs resampling
- storage vs CPU vs bandwidth trade-offs, with numbers
- schema evolution with a composite unique key
- graceful fallback during backfills
