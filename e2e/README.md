# SecureLeaf E2E smoke test (Phase 9, D8)

One Playwright test, `tests/mvp1-journey.spec.ts`, that drives the whole MVP1 golden path against
a real, fully running stack — no mocked GraphQL, no stubbed job queue:

1. register a creator
2. become a creator
3. upload `fixtures/sample.pdf` (a real 3-page PDF) and wait for the real async pipeline
   (Validate → Convert to tiles → Generate thumbnail → Generate preview → Mark LIVE)
4. register a buyer and buy the product (mock payment gateway, success path)
5. open the secure viewer and confirm the page is drawn on a real `<canvas>` with non-blank
   pixels, and that no `<img>` is used for page content (VIEW-01)

## Running it locally

```bash
# 1. Infra (Postgres, Redis, MinIO)
cd infra && docker compose up -d

# 2. Backend (separate terminal)
cd backend && ./mvnw spring-boot:run

# 3. Frontend (separate terminal)
cd frontend && npm run dev

# 4. The test itself
cd e2e
npm install
npx playwright install --with-deps chromium
npm run e2e
```

`E2E_BASE_URL` overrides the frontend origin (default `http://localhost:5173`) if you're running
against a different port or a deployed environment.

## Why this is its own `package.json`

This project is deliberately not a workspace of `frontend/` or `backend/` — it drives the whole
stack as a black box over HTTP, the same way a real browser would, and has no business importing
either app's source. `npm run e2e` (or `npm test`) runs it; `npm run typecheck` type-checks it
without running anything.

## CI

`docs/ci/e2e.yml.example` is a ready-to-copy GitHub Actions workflow — see the comment at the top
of that file for why it isn't already a live workflow.
