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

This starts: PostgreSQL, Redis, MinIO, and Mailpit (dev mail catcher).

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

## Key Architecture: DRM Secure Viewer

The star feature — content is **never** sent as a downloadable file:

1. PDFs are converted server-side into **image tiles** (Apache PDFBox)
2. Each tile is **watermarked server-side** with the buyer's identity (Java2D) — the browser never receives a clean tile
3. Tiles are served via **signed URLs with 30-second TTL** from MinIO
4. The browser renders tiles on an **HTML5 Canvas** with right-click, print, text-select, and drag all disabled
5. **Single-session enforcement** via Redis `SET NX` — opening on a second device kills the first session
