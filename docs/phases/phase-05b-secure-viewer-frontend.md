# Phase 05B — Secure viewer (frontend)

> Spec for an unattended run. Decisions are numbered (D1…) and referenced from code comments.
> Requirements: **VIEW-04, VIEW-05, VIEW-06, VIEW-07, VIEW-08, VIEW-09, VIEW-11** (plus the UI side of VIEW-01/10/12).
> Depends on: Phase 05A (session + signed tile API). Uses its GraphQL documents and types.

## Context

Phase 05A built the server side: sessions, single-use signed tile URLs, and watermark burning. The
"Read" button in `LibraryPage.tsx:70-74` is still disabled ("coming in Phase 5"). This phase builds
the **canvas viewer**, and the **piracy-friction** controls a browser can reasonably offer.

The learning note has to be honest about these controls. They *raise the effort* for casual copying, and every one of them can be bypassed. The real protection is the server-side watermark, which identifies the buyer (see the 05A note).

## Decisions

- **D1 — Route.** `/read/:productId` inside `ProtectedRoute` (any logged-in user; the server checks the entitlement). The layout is full-screen, without the normal `AppLayout` chrome.
- **D2 — Pages are drawn on `<canvas>` only (VIEW-01).** Flow:
  1. Get a signed URL with `viewerPageUrl`.
  2. `fetch(url, { headers: { Authorization }, cache: 'no-store' })`.
  3. `blob()` → `createImageBitmap()` → `ctx.drawImage()` → `bitmap.close()`.

  Never create an `<img>`, an object URL, or a CSS `background-image` for a page. Any of those makes "Save image as…" trivial. The canvas is sized to the container with devicePixelRatio scaling, so text stays sharp.
- **D3 — Session hook.** `useViewerSession(productId)` runs `startViewerSession` on mount.
  - It heartbeats every `heartbeatIntervalSeconds` using `setInterval`, cleared on unmount.
  - On `pagehide`/unmount it calls `endViewerSession` with `fetch(..., { keepalive: true })`. Don't use `navigator.sendBeacon`: it can't send an `Authorization` header. The learning note should cover this gotcha.
  - It exposes `{ status: 'starting' | 'active' | 'superseded' | 'expired' | 'error', session, restart() }`.
- **D4 — Superseded / expired UI (VIEW-10).**
  - Heartbeat returns `SUPERSEDED`, or a tile fetch gets 409: show a full-screen panel, "This book was opened on another device or tab", with a **"Read here instead"** button that calls `restart()` (starts a new session and takes over).
  - `EXPIRED`, or a tile fetch gets 410: restart silently, once.
  - A 403 or `NOT_ENTITLED`: show a "You don't have access" panel linking to the product page.
- **D5 — Page loading.** `useSecureTile(session, pageNumber)` fetches one page. Once page *n* is drawn, prefetch page *n+1* into an `ImageBitmap` held in memory. Signed URLs are single-use and expire after 30 s, so **never cache URLs**, only decoded bitmaps. Keep at most 3 bitmaps and close the ones that get evicted.
- **D6 — Navigation (VIEW-11).** Prev/Next buttons, ←/→ keys, and a "Page n / N" indicator. The page number is stored in the URL (`?page=`) so a reload resumes. Clamp to `1..pageCount`.
- **D7 — Friction controls**, each in its own small hook under `src/hooks/viewer/` so it can be tested:
  - VIEW-04 `useBlockContextMenu`: `preventDefault` on `contextmenu` inside the viewer.
  - VIEW-05/06: `select-none`, `draggable={false}`, and `onDragStart` prevented on the viewer root. Tailwind utilities where possible.
  - VIEW-07: a global print stylesheet, `@media print { body { display: none !important } }` in `index.css`. Also block Ctrl/Cmd+P and Ctrl/Cmd+S in the viewer.
  - VIEW-09 `useBlurOnFocusLoss`: blur the canvas (CSS `blur(24px)`) plus an overlay on `visibilitychange` → hidden, or window `blur`. Remove it on focus/visible.
  - VIEW-08 `useDevToolsHeuristic`: flags when `outerWidth - innerWidth > 160` or `outerHeight - innerHeight > 160`, checked on resize and every 1 s, and blurs like VIEW-09. The code comment and the learning note must say this is a **heuristic**: undocked DevTools evade it, and it can false-positive on some zoom levels. That's why it blurs rather than ending the session.
  - A PrintScreen `keyup` briefly blanks the canvas. Best effort; say so.
- **D8 — State.** Remote data goes through Apollo. Local viewer UI state (current page, blurred flags, zoom) goes in a small Zustand store (`src/stores/viewerStore.ts`), per CLAUDE.md.
- **D9 — Entry points.** In `LibraryPage` the "Read" button becomes enabled and links to `/read/:productId`. In `BuyPanel` the owned state shows **"Read now"**, linking to the viewer (currently "In your library"). Update any tests that assert on the old text.
- **D10 — Design.** Dark reading surface, subtle glass toolbar, keyboard hints, and a loading skeleton while a page decodes. Premium but calm, per CLAUDE.md's Tailwind guidance. The page must work at 360 px width. Tap targets are fine; touch-optimised gestures are post-MVP.

## Acceptance criteria (Vitest + React Testing Library; mock Apollo with `MockedProvider`, and mock `fetch`/`createImageBitmap`)
1. The viewer starts a session and draws page 1 through `drawImage`. No `<img>` element exists in the viewer DOM.
2. A `contextmenu` event on the viewer is `defaultPrevented`. The root has `select-none` and elements aren't draggable.
3. A `visibilitychange` to hidden adds the blur overlay; visible removes it.
4. A heartbeat mock returning `SUPERSEDED` shows the takeover panel. "Read here instead" starts a new session.
5. A tile fetch returning 409 shows the same panel. A 410 restarts once.
6. Next/Prev and the arrow keys change the page, clamped at both ends. The indicator text updates. `?page=` is respected on load.
7. The heartbeat interval is cleared and `endViewerSession` is sent (keepalive) on unmount.
8. The Library "Read" button links to `/read/:id`. BuyPanel shows "Read now" for owned products.
9. The DevTools heuristic hook blurs when the window dimensions differ by more than the threshold (unit test with stubbed `outerWidth`/`innerWidth`).

`npm run lint`, `npx tsc --noEmit`, `npm run test` and `npm run build` must all pass. No `any`.

## Out of scope
- Zoom beyond fit-width.
- Search, bookmarks, annotations.
- Offline reading.
- A mobile gesture layer.
- Real-time progress sync.

## Learning note
Extend `docs/learning-notes/phase-05-secure-viewer.md`: add sections for 05B and update the status. Headline topics:
- why canvas instead of `<img>`
- `createImageBitmap` and memory hygiene (`close()`)
- `keepalive` fetch vs `sendBeacon` (headers)
- the honest limits of browser DRM (list what each control stops and how it's bypassed)
- custom hooks as units of behaviour
- Apollo for remote state vs Zustand for local state
