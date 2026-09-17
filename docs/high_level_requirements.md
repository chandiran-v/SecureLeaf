# VaultContent — High-Level Requirements

> A DRM-protected digital content marketplace where creators sell knowledge (PDFs, documents, images) with secure in-browser viewing — buyers can view but never download, screenshot, or pirate the content.

---

## 1. Project Vision

Creators (teachers, professionals, designers, students) upload their digital knowledge products. Buyers discover, purchase, and **view content securely inside the browser** — the content is never downloadable. The platform handles payments, content protection, and creator payouts.

**Think**: Gumroad + Kindle's DRM viewer — built as a full-stack showcase project.

---

## 2. User Roles

| Role | Who They Are | What They Can Do |
|------|-------------|-----------------|
| **Guest** | Unauthenticated visitor | Browse marketplace, view product previews (blurred), register/login |
| **Buyer** | Registered user who purchases content | Buy products, view purchased content in secure viewer, manage library, leave reviews |
| **Creator** | Registered user who sells content | Everything a Buyer can do + upload content, set pricing, view sales analytics, request payouts |
| **Admin** | Platform administrator | Content moderation, user management, payout approval, platform-wide analytics |

> [!NOTE]
> A single user can be both a **Creator** and a **Buyer** simultaneously (like how anyone on Gumroad can both sell and buy).

---

## 3. Functional Requirements

### Module 1: Authentication & User Management

| ID | Requirement |
|----|------------|
| AUTH-01 | User registration with email & password |
| AUTH-02 | User login returning JWT access token + refresh token |
| AUTH-03 | Refresh token rotation (old refresh token invalidated on use) |
| AUTH-04 | Role-based access control — Guest, Buyer, Creator, Admin |
| AUTH-05 | Creator registration requires additional profile info (display name, bio, payout details) |
| AUTH-06 | Password reset via email token |
| AUTH-07 | Account deactivation / suspension by Admin |

---

### Module 2: Content Upload & Processing (Creator Side)

| ID | Requirement |
|----|------------|
| UPLOAD-01 | Creator uploads digital files (PDF, DOCX, PPTX, images — PNG/JPG) |
| UPLOAD-02 | File size limit: 50MB per file |
| UPLOAD-03 | Supported formats validated on upload (reject unsupported types) |
| UPLOAD-04 | **Async processing pipeline**: Upload → Validate → Convert to image tiles → Generate thumbnail → Generate blurred preview → Mark as "LIVE" |
| UPLOAD-05 | Creator sets: Title, Description, Category, Tags, Cover Image |
| UPLOAD-06 | Creator sets pricing: One-time price (e.g., ₹199) |
| UPLOAD-07 | Creator can set a product as FREE (₹0) — still protected viewing |
| UPLOAD-08 | Creator can configure free preview pages (e.g., first 3 pages visible to everyone, rest blurred) |
| UPLOAD-09 | Creator can unpublish / delete their product |
| UPLOAD-10 | Upload status tracking: PROCESSING → LIVE → UNPUBLISHED |

---

### Module 3: Marketplace & Discovery (Buyer Side)

| ID | Requirement |
|----|------------|
| MARKET-01 | Public marketplace listing all LIVE products |
| MARKET-02 | Search by title, creator name, tags |
| MARKET-03 | Filter by: Category, Price range (Free / Paid), Rating |
| MARKET-04 | Sort by: Newest, Most Popular, Highest Rated, Price Low→High |
| MARKET-05 | Product detail page: Cover image, title, description, creator info, price, rating, number of buyers, free preview |
| MARKET-06 | Free preview: First N pages rendered in secure viewer (configurable by creator), remaining pages shown as blurred |
| MARKET-07 | Pagination on marketplace listing |

---

### Module 4: Purchase & Payment

| ID | Requirement |
|----|------------|
| PAY-01 | "Buy Now" button on product detail page |
| PAY-02 | Payment integration (Stripe / Razorpay) — or mock payment for MVP |
| PAY-03 | On successful payment: License record created granting buyer access |
| PAY-04 | Idempotency: Same buyer cannot be charged twice for the same product (duplicate payment prevention) |
| PAY-05 | Purchase receipt / confirmation via email notification |
| PAY-06 | Purchase history visible in Buyer's library |
| PAY-07 | Creator revenue = Product price minus platform commission (e.g., 10%) |

---

### Module 5: Secure Content Viewer (Core DRM Feature)

> [!IMPORTANT]
> This is the **star feature** of the entire project — the technical showpiece for your resume.

| ID | Requirement |
|----|------------|
| VIEW-01 | Content rendered as **image tiles on HTML5 Canvas** — raw files never sent to browser |
| VIEW-02 | Each image tile served via **signed URL with 30-second TTL** — cannot be bookmarked or shared |
| VIEW-03 | **Dynamic watermark** on every page: semi-transparent text with buyer's email/user ID overlaid on content |
| VIEW-04 | **Right-click disabled** on viewer |
| VIEW-05 | **Text selection disabled** (CSS `user-select: none`) |
| VIEW-06 | **Drag disabled** on all content elements |
| VIEW-07 | **Print disabled** — CSS `@media print { body { display: none } }` |
| VIEW-08 | **DevTools detection** — content hidden/blurred when browser developer tools are opened |
| VIEW-09 | **Blur on focus loss** — content blurs when user switches tabs or window loses focus |
| VIEW-10 | **Single-session enforcement** — only 1 active viewing session per user per product. Opening on a second device/tab terminates the first |
| VIEW-11 | Page-by-page navigation (Previous / Next) with page number indicator |
| VIEW-12 | Only authenticated users with a valid purchase/license can access the viewer |

---

### Module 6: Buyer Library

| ID | Requirement |
|----|------------|
| LIB-01 | "My Library" page showing all purchased products |
| LIB-02 | Each item shows: Cover image, title, creator, purchase date, "View" button |
| LIB-03 | Access status indicator: Active / Expired (for future subscription model) |

---

### Module 7: Creator Dashboard

| ID | Requirement |
|----|------------|
| DASH-01 | List of all uploaded products with status (PROCESSING / LIVE / UNPUBLISHED) |
| DASH-02 | Per-product stats: Total sales count, total revenue earned |
| DASH-03 | Overall earnings summary: Total revenue, platform commission, net payout |
| DASH-04 | Upload new product button → Upload flow |

---

### Module 8: Reviews & Ratings

| ID | Requirement |
|----|------------|
| REV-01 | Buyers can rate a purchased product (1-5 stars) |
| REV-02 | Buyers can write a text review |
| REV-03 | One review per buyer per product |
| REV-04 | Average rating displayed on product card and detail page |

---

### Module 9: Notifications

| ID | Requirement |
|----|------------|
| NOTIF-01 | Buyer receives notification on successful purchase |
| NOTIF-02 | Creator receives notification when someone buys their product |
| NOTIF-03 | Creator receives notification when content processing is complete |
| NOTIF-04 | Notifications delivered via: In-app (WebSocket) + Email (async) |

---

### Module 10: Admin Panel

| ID | Requirement |
|----|------------|
| ADMIN-01 | View all users (Buyers + Creators) with search and filter |
| ADMIN-02 | Suspend / reactivate user accounts |
| ADMIN-03 | View all products — approve / reject / take down reported content |
| ADMIN-04 | Platform-wide analytics: Total users, total sales, total revenue, top products |

---

## 4. Non-Functional Requirements

| Category | Requirement |
|----------|------------|
| **Performance** | Content viewer must load a page tile (including server-side watermark burn via Java2D) in under 500ms |
| **Concurrency** | System must handle 500+ concurrent viewers on the same product without race conditions |
| **Security** | Raw content files must NEVER be accessible via any public URL |
| **Security** | Browser must never receive an unwatermarked image tile |
| **Security** | All API endpoints (except public marketplace GraphQL queries) require valid JWT |
| **Security** | Signed content URLs expire within 30 seconds |
| **Security** | Object-level authorization — creators can only modify their own products |
| **Scalability** | File processing pipeline must handle 10+ simultaneous uploads via thread pool |
| **Reliability** | Failed file processing must retry up to 3 times before marking as FAILED |
| **Reliability** | Duplicate purchases must be prevented via idempotency keys + idempotent webhook processing |
| **Reliability** | Product soft deletes — existing buyers never lose access to purchased content |
| **Observability** | All major events (upload, purchase, view) must be logged with correlation IDs |
| **Observability** | Viewer access audit trail (`viewer_access_logs`) for DRM compliance |

---

## 5. MVP Scope vs Future Scope

### ✅ MVP 1 (Build This First)

| Module | What's Included |
|--------|----------------|
| Auth | Registration, Login (Email/Password + Google OAuth2), JWT + Refresh (hashed tokens), RBAC + object-level auth |
| Upload | PDF upload, PostgreSQL job queue processing pipeline, thumbnail generation |
| Marketplace | GraphQL product listing, search, filter, product detail with free preview |
| Commerce | Mock payment, orders → payments → entitlements pipeline, idempotency keys |
| **Secure Viewer** | Canvas rendering, signed URLs, **server-side watermark burning (Java2D)**, session heartbeats + device fingerprint, single session enforcement, viewer access logs |
| Buyer Library | List purchased products (entitlements), view button |
| Creator Dashboard | List products, sales count, upload new |

### 🚀 MVP 2 (Scale to 5,000 Concurrent Viewers)

> **Trigger**: MVP 2 begins when load testing or real traffic exposes MVP 1 performance ceilings. All patterns below are architecturally prepared for in MVP 1 (Strategy interfaces, config flags).

| Feature | What It Does | Why Deferred |
|---------|-------------|---------------|
| libvips watermark rendering | Replace Java2D with libvips (5–8× faster, low memory) via Strategy swap | JNI native deps, Docker complexity. Java2D handles 500 users on 4-core. |
| Per-user tile caching | Cache watermarked tiles (15-min TTL, ~40% hit rate) | 200KB–1MB tiles at scale = 10–50GB RAM. Need usage data to size correctly. |
| Decoupled render pool | Move burns off Tomcat threads, backpressure via 429 | Tomcat's 200 threads not saturated at 500 users. Adds async complexity. |
| Bucket4j rate limiting | 2 tile req/sec per buyer, blocks scrapers | Manual monitoring suffices at MVP scale. Add when abuse detected. |
| Document versioning | Buyers retain access to purchased version | One doc per product for MVP. Schema already supports it. |
| Adaptive tile resolution | Multiple resolutions for mobile vs desktop | 2–3× storage, routing logic. Single resolution for desktop-first MVP. |

### 🔮 Future Enhancements (Post-MVP)

| Feature | Description |
|---------|------------|
| Subscription model | Monthly/yearly access to a creator's full library |
| Real payment gateway | Stripe / Razorpay integration |
| Creator payouts | Automated payout calculation and transfer |
| Advanced analytics | Page-level heatmaps, read completion rate, time spent |
| DOCX / PPTX support | Extend processing pipeline to handle more formats |
| Content reporting | Buyers can report low-quality / fraudulent content |
| Social features | Follow creators, wishlists, share links |
| Mobile-responsive viewer | Touch-optimized secure viewer for tablets |

---

## 6. Tech Stack Summary

| Layer | Technology | Why |
|-------|-----------|-----|
| **Frontend** | React 18, TypeScript, Tailwind CSS v3, Apollo Client | Interactive SPA with secure Canvas viewer; Apollo for GraphQL queries |
| **API Layer** | GraphQL (Spring for GraphQL) + REST | GraphQL for data queries; REST for file uploads & signed tile URLs |
| **Backend** | Java 21, Spring Boot 3 | Enterprise-grade APIs with modern Java features |
| **Security** | Spring Security 6, JWT | Role-based + object-level auth with token rotation |
| **Async Processing** | CompletableFuture, ThreadPoolTaskExecutor, @Async | Parallel PDF processing & watermark generation |
| **Notifications** | Redis Pub/Sub | Lightweight real-time notifications (MVP) |
| **Caching & Sessions** | Redis | Signed URL tokens, session locking, product cache |
| **Database** | PostgreSQL | Relational data + `processing_jobs` table as durable job queue |
| **File Storage** | MinIO (S3-compatible) | Store original uploads & processed image tiles |
| **PDF Processing** | Apache PDFBox | Server-side PDF → image tile conversion |
| **Watermark Rendering** | Java2D `BufferedImage` (MVP) | Server-side watermark burning; Strategy interface for future libvips swap |
| **Containerization** | Docker Compose | Local dev: all services in one command |
| **CI/CD** | GitHub Actions | Automated build → test → Docker → deploy pipeline |

---

## 7. High-Level Architecture

```
┌───────────────────────────────────────────────────────────────────┐
│                        REACT FRONTEND                               │
│  ┌────────────┐  ┌──────────────┐  ┌────────────┐  ┌────────────┐  │
│  │ Marketplace │  │ Secure Viewer│  │  Creator   │  │   Buyer    │  │
│  │   Pages     │  │  (Canvas)    │  │ Dashboard  │  │  Library   │  │
│  └──────┬─────┘  └──────┬───────┘  └─────┬──────┘  └─────┬──────┘  │
│         │               │                │               │          │
│         └───────────────┴────────────────┴───────────────┘          │
│             Apollo Client (GraphQL) + Axios (REST)                   │
└──────────────────────────────┼────────────────────────────────────┘
                               │
┌──────────────────────────────┼────────────────────────────────────┐
│                    SPRING BOOT BACKEND                               │
│                              │                                       │
│  ┌──────────────┐  ┌────────┴────────┐  ┌─────────────────────┐    │
│  │ Spring       │  │  GraphQL         │  │  Content Processing │    │
│  │ Security     │──│  Resolvers +     │  │  Pipeline           │    │
│  │ + JWT Filter │  │  REST Controllers│  │  (Multi-threaded)   │    │
│  └──────────────┘  └────────┬────────┘  └──────────┬──────────┘    │
│                              │                      │               │
│  ┌──────────────┐  ┌────────┴────────┐  ┌──────────┴──────────┐    │
│  │   Service    │  │ Redis Pub/Sub   │  │   Redis             │    │
│  │   Layer      │──│ (Notifications) │  │   - Signed URLs     │    │
│  │              │  │                 │  │   - Session Lock    │    │
│  │              │  │                 │  │   - Cache           │    │
│  └──────┬───────┘  └─────────────────┘  │                     │    │
│         │                                └─────────────────────┘    │
│  ┌──────┴───────┐  ┌─────────────────┐                             │
│  │ PostgreSQL   │  │    MinIO        │                             │
│  │ - Users      │  │  (File Store)   │                             │
│  │ - Products   │  │  - Raw uploads  │                             │
│  │ - Orders     │  │  - Image tiles  │                             │
│  │ - Entitle.   │  │  - Thumbnails   │                             │
│  │ - Jobs queue │  │                 │                             │
│  └──────────────┘  └─────────────────┘                             │
└────────────────────────────────────────────────────────────────────┘
```
