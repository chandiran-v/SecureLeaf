# SecureLeaf — Project Plan

## Problem

Digital knowledge creators — teachers, professionals, designers, and students — have no safe way to monetize their documents.

- Selling a PDF directly means buyers can download, copy, and freely share it — one sale feeds hundreds of free copies.
- Existing subscription platforms take control away from creators and force recurring pricing models.
- There is **no affordable, independent platform** where a creator can sell a document and a buyer can *read* it securely — but *never truly own a copyable file*.

---

## Solution

**SecureLeaf** is a DRM-protected digital content marketplace.

Creators upload PDFs. Buyers purchase and view content **exclusively inside a secure, browser-based viewer** — the document is never sent as a downloadable file. Every page is rendered as a signed, time-limited image tile on an HTML5 Canvas, dynamically watermarked with the buyer's identity.

Think: *Gumroad for selling + Kindle DRM for viewing* — built as an independent full-stack platform.

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| **Frontend** | React 18 + TypeScript + Tailwind CSS v3 + Apollo Client |
| **API Layer** | GraphQL (Spring for GraphQL) — primary data API + REST for file uploads & signed tile URLs |
| **Backend** | Java 21, Spring Boot 3 |
| **Auth & Security** | Spring Security 6 + JWT (access + refresh token rotation) + Google OAuth2 |
| **RBAC** | Role-based (`@PreAuthorize` + JWT claims) + object-level ownership checks in service layer |
| **Async Processing** | `@Async`, `CompletableFuture`, `ThreadPoolTaskExecutor` + PostgreSQL job queue |
| **Real-Time Notifications** | Redis Pub/Sub (MVP) — Kafka deferred to post-MVP when event volume justifies it |
| **Caching & Sessions** | Redis — session locks, signed URL tokens |
| **Primary Database** | PostgreSQL |
| **File Storage** | MinIO (S3-compatible) — stores raw PDFs + processed tiles |
| **PDF Processing** | Apache PDFBox (page → tile conversion) |
| **Watermark Rendering** | Java2D `BufferedImage` (MVP) — adequate for 500 concurrent viewers on 4-core |
| **Containerization** | Docker Compose |
| **CI/CD** | GitHub Actions → Docker Build → Container Registry → Deploy |

---

## Key Architecture Decisions

### 1. Server-Side Watermark Burning (Not Client-Side)

The browser must **never receive a clean image tile**. Watermarks are burned into every tile on the backend using Java2D `BufferedImage` at request time before streaming to the client. Clean tiles live only in MinIO; the buyer's identity is composited server-side per request. This closes the DevTools → Network tab → download clean tile attack vector.

> **Architecture note**: The `WatermarkRenderer` is coded as a **Strategy interface**, so the rendering engine can be swapped from Java2D to libvips (or a Go/Rust sidecar) in MVP 2 via a config flag — zero changes to the tile service, controllers, or tests.

### 2. DRM Positioning: Piracy Deterrence, Not Absolute Prevention

No browser-based solution can prevent screen recording or camera capture. SecureLeaf positions its DRM as **traceable piracy deterrence** — every leaked image carries a burned-in watermark identifying the source buyer. This is the same model used by Kindle, Spotify canvas, and Netflix. Browser-side controls (right-click block, print block, focus blur) raise the friction floor for casual piracy.

### 3. PostgreSQL Job Queue over Kafka (MVP)

For a solo-project MVP processing ~10 uploads/day, Kafka introduces unnecessary operational complexity (Zookeeper, broker config, partition management). Instead:
- **`processing_jobs` table** in PostgreSQL acts as a durable job queue with state machine transitions
- **`@Async` + `ThreadPoolTaskExecutor`** processes jobs from the queue
- **Redis Pub/Sub** pushes lightweight real-time notifications (purchase alerts, processing status)
- Kafka is a **post-MVP upgrade** when event volume or multi-consumer streaming justifies it

### 4. Object-Level Authorization (Beyond RBAC)

Endpoint-level `@PreAuthorize("hasRole('CREATOR')")` is necessary but not sufficient — a Creator must only modify **their own** products. Every mutating service method validates `resource.creatorId == currentUserId` before proceeding.

---

## Phase 0 — Database Design (Current Focus)

Before any code is written, we finalize the complete data model.

### Domain Entities (Revised)

**Identity & Access**

| Entity | Purpose |
|--------|---------|
| `users` | All platform users — unified login (single account can buy + sell) |
| `user_roles` | Multi-role assignments per user (BUYER, CREATOR, ADMIN) |
| `refresh_tokens` | Hashed refresh tokens for rotation and revocation support |

**Product & Content Lifecycle**

| Entity | Purpose |
|--------|---------|
| `products` | Marketplace listing metadata (title, description, price, category, creator) — soft delete via `deleted_at` |
| `categories` | Product category master table |
| `product_tags` | Many-to-many tags for search and filtering |
| `document_versions` | Versioned uploads per product — **MVP assumes single version per product** (schema supports multi-version for MVP 2) |
| `content_pages` | Image tile records per document version — stores `minio_object_key`, `bucket_name`, `page_number` |

**Commerce & Entitlements**

| Entity | Purpose |
|--------|---------|
| `orders` | Purchase intent record (buyer, product, amount, status) |
| `payments` | Payment execution record — includes `idempotency_key`, `provider_payment_id` for dedup |
| `payment_events` | Immutable audit log of every payment state transition |
| `entitlements` | Actual access grant — links buyer to product + specific document_version; soft-revocable |

**DRM Viewer & Audit**

| Entity | Purpose |
|--------|---------|
| `viewer_sessions` | Active sessions with `device_fingerprint`, `ip_address`, `last_heartbeat_at` for single-session enforcement |
| `viewer_access_logs` | Immutable audit trail — who viewed what page, when, from where |

**Async Processing**

| Entity | Purpose |
|--------|---------|
| `processing_jobs` | PostgreSQL-backed job queue — tracks upload → validate → convert → thumbnail → preview → LIVE pipeline with retry count and failure reason |

**Engagement & Payouts**

| Entity | Purpose |
|--------|---------|
| `reviews` | Buyer ratings (1-5) and text reviews — one per buyer per product |
| `notifications` | In-app notification records (purchase, sale, processing complete) |
| `creator_payouts` | Payout request records with approval workflow |

### Key Relationships

- One `user` → many `products` (as creator) — ownership enforced at service layer
- One `product` → many `document_versions` → many `content_pages` (content lifecycle)
- One `order` → one `payment` → one `entitlement` (commerce pipeline)
- One `entitlement` links `buyer` + `product` + specific `document_version` (versioned access)
- One `user` + one `product` → one active `viewer_session` (single-session enforcement via Redis `SET NX`)
- One `user` + one `product` → one `review`

**Deliverable**: `db_schema.md` — complete PostgreSQL schema with table definitions, column types, constraints, indexes, enum types, and ER diagram.

