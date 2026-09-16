# SecureLeaf — Requirements

## User Roles

| Role | Who They Are | What They Can Do |
|------|-------------|-----------------|
| **Guest** | Unauthenticated visitor | Browse marketplace, view blurred previews, register/login |
| **Buyer** | Registered user who purchases | Buy products, view purchased content in secure viewer, manage library, leave reviews |
| **Creator** | Registered user who sells | Everything a Buyer can do + upload content, set pricing, view sales stats, request payouts |
| **Admin** | Platform administrator | Content moderation, user management, payout approval, platform analytics |

> A single user can be both a **Creator** and a **Buyer** simultaneously.

---

## Module 1: Authentication & User Management

| ID | Requirement |
|----|------------|
| AUTH-01 | User registration with email and password |
| AUTH-02 | **Google OAuth2 Sign-In** (Sign in with Google / Gmail ID) |
| AUTH-03 | User login returning JWT access token + refresh token |
| AUTH-04 | Refresh token rotation — old token invalidated on use |
| AUTH-05 | Role-based access control — Guest, Buyer, Creator, Admin |
| AUTH-06 | Creator registration requires additional profile info (display name, bio, payout details) |
| AUTH-07 | Password reset via email token (for email/password accounts) |
| AUTH-08 | Account deactivation / suspension by Admin |

---

## Module 2: Content Upload & Processing (Creator Side)

| ID | Requirement |
|----|------------|
| UPLOAD-01 | Creator uploads digital files — PDF only for MVP |
| UPLOAD-02 | File size limit: 50MB per file |
| UPLOAD-03 | Supported formats validated on upload; unsupported formats rejected |
| UPLOAD-04 | Async processing pipeline backed by PostgreSQL `processing_jobs` table: Upload → Validate → Convert to image tiles → Generate thumbnail → Generate blurred preview → Mark as LIVE |
| UPLOAD-05 | Creator sets: Title, Description, Category, Tags, Cover Image |
| UPLOAD-06 | Creator sets pricing: one-time price (e.g., ₹199) |
| UPLOAD-07 | Creator can set a product as FREE (₹0) — still protected viewing |
| UPLOAD-08 | Creator configures free preview pages (e.g., first 3 pages visible to all; rest blurred) |
| UPLOAD-09 | Creator can unpublish their product (soft delete via `deleted_at`) — existing buyers retain access |
| UPLOAD-10 | Upload status tracking via `processing_jobs`: `QUEUED → PROCESSING → COMPLETED → FAILED` (retry up to 3×) |
| UPLOAD-11 | Image tiles stored in MinIO with `minio_object_key` + `bucket_name` — raw PDFs never publicly accessible |

---

## Module 3: Marketplace & Discovery (Buyer Side)

| ID | Requirement |
|----|------------|
| MARKET-01 | Public marketplace listing all LIVE products |
| MARKET-02 | Search by title, creator name, tags |
| MARKET-03 | Filter by: Category, Price range (Free / Paid), Rating |
| MARKET-04 | Sort by: Newest, Most Popular, Highest Rated, Price Low → High |
| MARKET-05 | Product detail page: cover image, title, description, creator info, price, rating, buyer count, free preview |
| MARKET-06 | Free preview: first N pages rendered in secure viewer (configurable by creator), remaining pages shown blurred |
| MARKET-07 | Pagination on marketplace listing |

---

## Module 4: Commerce Pipeline (Orders → Payments → Entitlements)

| ID | Requirement |
|----|------------|
| PAY-01 | "Buy Now" button on product detail page |
| PAY-02 | Mock payment provider for MVP (simulates success / failure / timeout) |
| PAY-03 | **Order created** on purchase intent — captures buyer, product, amount, status |
| PAY-04 | **Payment record** created on provider confirmation — includes `idempotency_key` and `provider_payment_id` to prevent duplicate charges |
| PAY-05 | **Entitlement granted** on successful payment — links buyer to product + specific `document_version`; this is the actual access grant |
| PAY-06 | **Immutable `payment_events` log** — every state transition (PENDING → COMPLETED → REFUNDED) recorded as append-only audit trail |
| PAY-07 | Idempotent webhook processing — duplicate payment provider events must not create duplicate entitlements |
| PAY-08 | Purchase confirmation notification to buyer (in-app via Redis Pub/Sub + email async) |
| PAY-09 | Purchase history visible in buyer's library |
| PAY-10 | Creator revenue = product price minus platform commission (10%) |

---

## Module 5: Secure Content Viewer (Core DRM Feature)

> This is the star feature of the entire project. DRM is positioned as **traceable piracy deterrence** — not absolute prevention. No browser-based solution can prevent screen recording or camera capture. Every leaked image carries a burned-in watermark identifying the source buyer, making piracy traceable. Browser-side controls raise the friction floor for casual piracy.

| ID | Requirement |
|----|------------|
| VIEW-01 | Content rendered as **image tiles on HTML5 Canvas** — raw files never sent to browser |
| VIEW-02 | Each image tile served via **signed URL with 30-second TTL** — cannot be bookmarked or reused |
| VIEW-03 | **Server-side watermark burning** — watermark (buyer email + user ID + timestamp) composited into image tile on the backend using Java2D `BufferedImage` **before** it reaches the browser. The browser never receives a clean tile. `WatermarkRenderer` coded as Strategy interface for future engine swap. |
| VIEW-04 | Right-click disabled on viewer (piracy friction) |
| VIEW-05 | Text selection disabled — CSS `user-select: none` (piracy friction) |
| VIEW-06 | Drag disabled on all content elements (piracy friction) |
| VIEW-07 | Print disabled — CSS `@media print { body { display: none } }` (piracy friction) |
| VIEW-08 | DevTools detection — content hidden or blurred when browser developer tools are opened (piracy friction) |
| VIEW-09 | Blur on focus loss — content blurs when user switches tabs or window loses focus (piracy friction) |
| VIEW-10 | **Single-session enforcement** — only 1 active viewing session per user per product; `viewer_sessions` table tracks `device_fingerprint`, `ip_address`, `last_heartbeat_at`; opening on a second device invalidates the first session via Redis `SET NX` |
| VIEW-11 | Page-by-page navigation (Previous / Next) with page number indicator |
| VIEW-12 | Only authenticated users with a valid `entitlement` can access the viewer |
| VIEW-13 | **Viewer access logging** — every page view recorded in `viewer_access_logs` (buyer, product, page, timestamp, IP) for audit and analytics |

---

## Module 6: Buyer Library

| ID | Requirement |
|----|------------|
| LIB-01 | "My Library" page showing all purchased products |
| LIB-02 | Each item shows: cover image, title, creator, purchase date, "View" button |
| LIB-03 | Access status indicator: Active / Expired (future subscription model ready) |

---

## Module 7: Creator Dashboard

| ID | Requirement |
|----|------------|
| DASH-01 | List of all uploaded products with status (PROCESSING / LIVE / UNPUBLISHED / FAILED) |
| DASH-02 | Per-product stats: total sales count, total revenue earned |
| DASH-03 | Overall earnings summary: total revenue, platform commission, net payout |
| DASH-04 | Upload new product button → upload flow |

---

## Module 8: Reviews & Ratings

| ID | Requirement |
|----|------------|
| REV-01 | Buyers can rate a purchased product (1–5 stars) |
| REV-02 | Buyers can write a text review |
| REV-03 | One review per buyer per product |
| REV-04 | Average rating displayed on product card and detail page |

---

## Module 9: Notifications

| ID | Requirement |
|----|------------|
| NOTIF-01 | Buyer receives notification on successful purchase |
| NOTIF-02 | Creator receives notification when someone buys their product |
| NOTIF-03 | Creator receives notification when content processing is complete |
| NOTIF-04 | Notifications delivered via: in-app (WebSocket) + email (async) |

---

## Module 10: Admin Panel

| ID | Requirement |
|----|------------|
| ADMIN-01 | View all users (Buyers + Creators) with search and filter |
| ADMIN-02 | Suspend or reactivate user accounts |
| ADMIN-03 | View all products — approve, reject, or take down reported content |
| ADMIN-04 | Platform-wide analytics: total users, total sales, total revenue, top products |

---

## Non-Functional Requirements

| Category | Requirement |
|----------|------------|
| **Performance** | Content viewer tile load (including server-side watermark burn via Java2D): under 500ms |
| **Concurrency** | 500+ concurrent viewers on the same product without race conditions |
| **Security** | Raw content files (clean tiles) must never be accessible via any public URL |
| **Security** | Browser must never receive an unwatermarked image tile |
| **Security** | All API endpoints (except public marketplace GraphQL queries) require valid JWT |
| **Security** | Signed content URLs expire within 30 seconds |
| **Security** | Object-level authorization — creators can only modify their own products |
| **Scalability** | File processing pipeline must handle 10+ simultaneous uploads via thread pool |
| **Reliability** | Failed file processing must retry up to 3 times (tracked in `processing_jobs`) before marking as FAILED |
| **Reliability** | Duplicate purchases prevented via `idempotency_key` on payments + idempotent webhook processing |
| **Reliability** | Product soft deletes — existing buyers never lose access to purchased content |
| **Observability** | All major events (upload, purchase, view) logged with correlation IDs |
| **Observability** | Viewer access audit trail (`viewer_access_logs`) for DRM compliance |

---

## MVP 1 Scope (Build First)

| Module | What's Included |
|--------|------------------------|
| Auth | Registration, Email/Password + Google OAuth2 Sign-In, JWT + refresh (hashed tokens in DB), RBAC + object-level auth |
| Upload | PDF upload, PostgreSQL job queue processing pipeline, thumbnail generation |
| Marketplace | GraphQL product listing, search, filter, product detail with free preview |
| Commerce | Mock payment, orders → payments → entitlements pipeline, idempotency keys |
| Secure Viewer | Canvas rendering, signed URLs, **server-side watermark burning (Java2D)**, session heartbeats + device fingerprint, single session enforcement, viewer access logs |
| Buyer Library | List purchased products (entitlements), view button |
| Creator Dashboard | List products, sales count, upload new product |

---

## MVP 2 Scope (Scale to 5,000 Concurrent Viewers)

> **Trigger**: MVP 2 work begins when load testing or real user traffic shows MVP 1 hitting performance ceilings (tile latency > 500ms, thread pool exhaustion, or scraper abuse). These patterns are architecturally prepared for in MVP 1 (Strategy interfaces, config flags), but not implemented until profiling proves the need.

| ID | Feature | Justification for Deferral |
|----|---------|----------------------------|
| MVP2-01 | **libvips watermark rendering** — Replace Java2D with libvips via JNI bindings (5–8× faster, streaming pixel pipeline, fraction of RAM). `WatermarkRenderer` Strategy interface already exists from MVP 1; this is a new implementation + config swap. | **Why deferred**: JNI introduces native library compilation per OS, Docker image complexity, and segfault-level debugging. Java2D comfortably handles 500 concurrent viewers on 4-core hardware. Swap only when profiling proves Java2D is the bottleneck. |
| MVP2-02 | **Per-user watermarked tile caching** — Cache already-burned tiles keyed by `hash(buyer_id + doc_version_id + page_number)` with 15-min TTL. Local disk cache (`/tmp/tile-cache/`) for single-server; Redis cache if horizontally scaled. ~40% hit rate on back-navigation. | **Why deferred**: Storing 200KB–1MB tiles in Redis at scale costs significant RAM (5,000 users × 10 pages = 10–50GB). Local disk cache is simple but only works on single-server. Need real usage patterns first to right-size the cache layer. |
| MVP2-03 | **Decoupled rendering thread pool** — Move watermark burning off Tomcat servlet threads to a dedicated bounded `ThreadPoolTaskExecutor` or Java 21 Virtual Threads. Backpressure via HTTP 429 or `DeferredResult` when queue saturates (e.g., 200 pending burns). | **Why deferred**: At 500 concurrent viewers, Tomcat's default 200-thread pool is not saturated by watermark burns. Decoupling adds complexity (async request lifecycle, error propagation, timeout handling). Implement when thread pool metrics show contention. |
| MVP2-04 | **Buyer-level rate limiting** — Per-buyer hard limit of 2 tile requests/second via Bucket4j + Redis token buckets. Blocks automated scrapers from mass-ripping entire books. | **Why deferred**: Bucket4j + Redis adds a dependency and filter chain complexity. At MVP scale with limited users, manual monitoring and IP-level bans suffice. Implement when scraper abuse is detected in viewer_access_logs. |
| MVP2-05 | **Document versioning (full)** — Creator uploads new version; buyers retain access to version they purchased. UI for version selection, entitlements tied to specific document_version. | **Why deferred**: For MVP, a product has one document. Re-upload replaces it. Full versioning requires version selection UI, storage doubling, and entitlement migration logic — real value at scale, drag for first release. Schema (`document_versions` table) already supports it. |
| MVP2-06 | **Adaptive tile resolution** — `content_pages` stores multiple resolutions per page. Mobile clients receive smaller tiles to reduce bandwidth and burn cost. | **Why deferred**: Requires resolution detection on frontend, multiple tile variants per page during processing (2–3× storage), and routing logic. Single resolution works for desktop-first MVP. |
| MVP2-07 | **Concurrency target: 5,000+** — All of MVP2-01 through MVP2-04 combined raise the ceiling from 500 to 5,000+ concurrent viewers. | Cumulative effect of the above four patterns. |

---

## Post-MVP (Future Scope)

| Feature | Description |
|---------|------------|
| Real payment gateway | Stripe / Razorpay integration (replace mock provider) |
| Apache Kafka | Replace PostgreSQL job queue + Redis Pub/Sub when event volume justifies streaming infrastructure |
| Subscription model | Monthly/yearly access to a creator's full library |
| Creator payout automation | Automated payout calculation and transfer |
| DOCX / PPTX support | Extend processing pipeline to more formats |
| Advanced analytics | Page-level heatmaps, read completion rate, time spent (from viewer_access_logs) |
| Mobile-responsive viewer | Touch-optimized secure viewer |
| Social features | Follow creators, wishlists, share links |
| Content reporting | Buyers can report low-quality or fraudulent content |
