# SecureLeaf

> A **DRM-protected digital content marketplace** — creators upload PDFs, buyers purchase and view content exclusively inside a secure, browser-based Canvas viewer. Content is never downloadable.

Think: *Gumroad for selling + Kindle DRM for viewing*.

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Frontend | React 18, TypeScript, Tailwind CSS v3, Apollo Client, Vite |
| API | GraphQL (Spring for GraphQL) + REST |
| Backend | Java 21, Spring Boot 3, Spring Security 6 |
| Auth | JWT (access + refresh rotation), Google OAuth2 |
| Database | PostgreSQL + Flyway migrations |
| Cache / Sessions | Redis |
| File Storage | MinIO (S3-compatible) |
| PDF Processing | Apache PDFBox |
| Watermarking | Java2D `BufferedImage` |
| Containerization | Docker Compose |
| CI/CD | GitHub Actions |

---

## Project Structure

```
SecureLeaf/
├── docs/                     # Planning & design documents
│   ├── project_plan.md
│   ├── high_level_requirements.md
│   ├── requirements.md
│   └── db_schema.md
│
├── backend/                  # Java 21 + Spring Boot 3 (Maven)
│   ├── src/main/java/com/secureleaf/
│   │   ├── auth/             # JWT, OAuth2, Security config
│   │   ├── content/          # Upload & processing pipeline
│   │   ├── marketplace/      # Products, categories, search
│   │   ├── commerce/         # Orders, payments, entitlements
│   │   ├── viewer/           # DRM tile service, sessions
│   │   ├── creator/          # Creator dashboard, payouts
│   │   ├── notification/     # Redis Pub/Sub, email
│   │   ├── admin/            # Admin panel
│   │   └── common/           # Shared utils, exceptions, config
│   ├── src/main/resources/
│   │   ├── application.yml
│   │   ├── application-dev.yml
│   │   └── graphql/schema.graphqls
│   └── pom.xml
│
├── frontend/                 # React 18 + TypeScript + Apollo (Vite)
│   ├── src/
│   │   ├── components/       # Shared UI components
│   │   ├── pages/            # Route-level pages (marketplace, viewer, creator, buyer, admin)
│   │   ├── hooks/            # Custom React hooks
│   │   ├── graphql/          # Apollo queries, mutations, client setup
│   │   ├── store/            # Zustand global state
│   │   ├── types/            # TypeScript domain types
│   │   └── utils/            # Helper utilities
│   ├── package.json
│   └── vite.config.ts
│
├── infra/                    # Infrastructure & DevOps
│   ├── docker-compose.yml    # Local dev: all services in one command
│   ├── docker/
│   │   ├── backend.Dockerfile
│   │   └── frontend.Dockerfile
│   └── nginx/nginx.conf
│
├── .github/workflows/
│   ├── backend-ci.yml        # Maven build + test (Postgres + Redis in CI)
│   └── frontend-ci.yml       # npm build + type-check
│
└── README.md
```

---

## Getting Started (Local Development)

### Prerequisites

- Java 21 (e.g., via [SDKMAN](https://sdkman.io/))
- Node 20+
- Docker Desktop

### 1. Start infrastructure services

```bash
cd infra
docker compose up -d
```

This starts: PostgreSQL, Redis, MinIO, and Mailpit (dev mail catcher — UI at http://localhost:8025).

### 2. Run the backend

```bash
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Backend runs at `http://localhost:8080`  
GraphiQL playground: `http://localhost:8080/graphiql`

### 3. Run the frontend

```bash
cd frontend
npm install
npm run dev
```

Frontend runs at `http://localhost:5173`

---

## Documentation

All design documents live in [`docs/`](docs/):

- [`project_plan.md`](docs/project_plan.md) — Problem statement, solution, architecture decisions
- [`high_level_requirements.md`](docs/high_level_requirements.md) — Functional & non-functional requirements
- [`requirements.md`](docs/requirements.md) — Detailed requirements
- [`db_schema.md`](docs/db_schema.md) — Complete PostgreSQL schema

---

## Marketplace & Free Preview (Phase 3)

Public, unauthenticated browsing at `/marketplace`: full-text search (PostgreSQL `tsvector`/GIN),
filter by category/price/free, sort, and paginate through every `LIVE` product. Each product's
detail page (`/product/:id`) shows a watermarked free preview of its first `freePreviewPages` pages
— rendered onto an HTML5 canvas, not an `<img>` — streamed through a backend endpoint that
watermarks on the way out so a clean page tile never reaches the browser. See
[`docs/learning-notes/phase-03-marketplace.md`](docs/learning-notes/phase-03-marketplace.md) for the
full design write-up.

---

## Commerce: Buying (Phase 4)

Logged-in buyers can buy a product (`Buy` → `/checkout/:orderId`) through a **mock payment gateway
shaped exactly like Razorpay**: order → checkout → HMAC-signed `razorpay_signature` → signed
`X-Razorpay-Signature` webhook. The checkout page lets you simulate **success, a declined card, or a
gateway timeout** (money taken, browser told nothing — the webhook still completes the order). Every
purchase is idempotent (client idempotency key + row locks), every payment state change is written to
an append-only audit log, and the buyer gets an **entitlement** — the access grant the Phase 5 secure
viewer checks on every single page turn. Also: **My Library** (`/library`), a creator **earnings card** (10% platform fee,
snapshotted per sale), and purchase **notifications** (in-app bell, Redis Pub/Sub, email via Mailpit
at <http://localhost:8025>).

Swapping in real Razorpay = one `PaymentGateway` implementation + three environment variables
(`RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, `RAZORPAY_WEBHOOK_SECRET`) + loading `checkout.js`. See
[`docs/learning-notes/phase-04-commerce.md`](docs/learning-notes/phase-04-commerce.md) §10.

---

## Secure DRM Viewer (Phase 5)

The star feature — content is **never** sent as a downloadable file. Logged-in buyers with an active
entitlement open a book at `/read/:productId`:

1. PDFs are converted server-side into **image tiles** (Apache PDFBox)
2. Each tile is **watermarked server-side** with the buyer's identity (Java2D) — the browser never receives a clean tile
3. Tiles are served through our own signing controller — **HMAC-signed, single-use URLs with a 30-second TTL** — never a presigned link straight to storage
4. The browser fetches each page with its JWT, decodes it off-DOM (`createImageBitmap`), and paints it onto an **HTML5 Canvas** — never an `<img>`, with right-click, print, text-select, and drag all disabled, and the page blurred the instant the tab loses focus or DevTools looks open
5. **Single-session enforcement** via a Redis `SET ... GET` (last-writer-wins) — opening on a second device evicts the first, which finds out via heartbeat or its next page turn

See [`docs/learning-notes/phase-05-secure-viewer.md`](docs/learning-notes/phase-05-secure-viewer.md) for the full write-up, including an honest table of what every browser-side control stops and how each one is bypassed.

---

## Library, Creator Dashboard & Live Notifications (Phase 6)

The buyer/creator loop, finished:

- **My Library** (`/library`) now shows *every* entitlement a buyer has ever held — ACTIVE, REVOKED,
  or EXPIRED — with a status badge and, for anything that isn't ACTIVE, a disabled "Read" button
  explaining why. Client-side search by title.
- **Creator dashboard**: per-product **sales count and net earnings** (one shared, aggregate query
  regardless of how many products or which of those two fields you ask for), the current pipeline
  **stage** for a PROCESSING upload, the **failure reason** for a FAILED one plus a one-click
  **Retry**, and **Republish** for an UNPUBLISHED product. Unpublish/Delete both confirm first and
  say outright that existing buyers keep their access.
- **Real-time notifications via Server-Sent Events**, replacing the old 30-second poll: a one-time,
  30-second Redis ticket authenticates the stream (`EventSource` can't send an `Authorization`
  header), and Redis Pub/Sub fans a new notification out to whichever backend instance is holding
  that user's open connection. Falls back to polling automatically after 3 failed reconnects.
  "Mark all read" clears the badge in one call.
- The pipeline itself now notifies the creator — `PROCESSING_COMPLETE` when a product goes LIVE,
  `PROCESSING_FAILED` when it exhausts its retries — through the same after-commit notification
  path purchases already used.

See [`docs/learning-notes/phase-06-library-dashboard-notifications.md`](docs/learning-notes/phase-06-library-dashboard-notifications.md)
for the full write-up: SSE vs. WebSocket vs. polling, why a ticket and not the JWT, the Pub/Sub
fan-out's at-most-once caveat (and why that's fine here), and a named `DataLoader` shared across two
GraphQL fields.

---

## Reviews & Ratings + Password Reset (Phase 7)

- **Reviews**: an entitled buyer can rate a product 1–5 stars with optional text — `submitReview` is
  an **upsert** (one review per buyer per product, editable any time), with **Edit**/**Delete** on
  the product page. The creator can never review their own product. `average_rating`/`review_count`
  stay exactly correct even when two buyers review the same product at the same instant, via a
  product-row lock plus a full recompute from the `reviews` table — not an incremental formula.
- **Fixed a privacy leak before it shipped**: the schema's `Review.buyer: User!` would have exposed
  the reviewer's email to anyone viewing a product's reviews. Replaced with `Review.reviewer:
  ReviewerSummary!` (display name only) — closed at the type level, not just in a resolver.
- The product page now shows a **rating histogram** (count per star) and a **paginated review
  list**; the marketplace can filter to **"4★ & up" / "3★ & up"**, and unrated products always sort
  last.
- **Password reset**: `requestPasswordReset(email)` always returns `true` — whether or not the email
  has an account — and is rate-limited to 3 emails/hour per address via Redis. A LOCAL account gets a
  reset link with a **hashed, single-use, 30-minute token**; a Google-only account gets a "you sign
  in with Google" email instead. `resetPassword` BCrypts the new password and **revokes every
  existing session** (refresh token) for that account, so a stolen, already-open session dies with
  the old password.

See [`docs/learning-notes/phase-07-reviews-password-reset.md`](docs/learning-notes/phase-07-reviews-password-reset.md)
for the full write-up: denormalized aggregates and the lost-update anomaly, why a pessimistic lock
beats an incremental formula or `SERIALIZABLE`, data minimisation, user enumeration, and reset-token
hashing/single-use/session-revocation.
