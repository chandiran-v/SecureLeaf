# Implementation Prompt — SecureLeaf MVP 1, Phase 2: Creator Upload & Async Processing Pipeline

> Paste everything below this line into Gemini as a single prompt. It is written to be self-contained — it assumes the model has the repository but no prior conversation context.

---

You are implementing **Phase 2** of the SecureLeaf project. Read this entire brief before writing any code. Follow it precisely — the repository already contains a complete database schema, a complete set of JPA entities, and a complete GraphQL schema, and your job is to fill in the missing service/resolver/controller layers **without changing those foundations**.

## 1. What SecureLeaf is

A DRM-protected digital content marketplace. Creators upload PDFs; buyers purchase and view them **exclusively inside a secure browser-based Canvas viewer**. The document is never sent as a downloadable file — every page is rendered server-side into an image tile, watermarked server-side with the buyer's identity, and served via a short-lived signed URL.

**Repository root:** `C:\Users\DELL\Downloads\SecureLeaf\SecureLeaf`

```
backend/    Java 21, Spring Boot 3.3.2, Maven  (com.secureleaf)
frontend/   React 18 + TypeScript + Vite 4 + Apollo Client + Zustand + Tailwind v3
infra/      docker-compose: Postgres 16, Redis 7, MinIO, pgAdmin
docs/       requirements.md, high_level_requirements.md, project_plan.md, db_schema.md
```

Read `docs/requirements.md` and `CLAUDE.md` before starting.

## 2. Non-negotiable project conventions

From `CLAUDE.md` — violating any of these is a bug:

- **Java 21.** Use records, pattern matching, modern APIs where natural.
- **Flyway only for DDL.** `spring.jpa.hibernate.ddl-auto=validate` is set. **Never** let Hibernate generate schema. If you need a schema change, add a new `V3__*.sql` migration — but for this phase you should need **none**, because every table already exists.
- **GraphQL for data, REST for file upload/download.** Do not add REST endpoints for things GraphQL already declares.
- **Money is always `paise`** — integers, smallest currency unit. Never floats for money.
- **Timestamps** are `TIMESTAMPTZ` in Postgres and `Instant`/`OffsetDateTime` in Java.
- **Never return `null`** from a service. Use `Optional` or throw a specific business exception.
- **Database naming** is `snake_case`; primary keys are `BIGSERIAL` → Java `Long`.
- **Soft deletes** via `deleted_at` — buyers must never lose access to purchased content.
- **Frontend:** functional components only, custom hooks for logic, Apollo for remote state, Zustand for local state, Tailwind utility classes, **no `any`**.

## 3. Current state of the codebase

**Fully complete — do not modify:**

- `backend/src/main/resources/db/migration/V1__init_schema.sql` (317 lines): 18 tables, 11 Postgres enum types, partial indexes, a GIN full-text index on products, and all uniqueness/idempotency constraints. `V2__fix_ip_address_type.sql` converts `ip_address` columns from `INET` to `VARCHAR(45)`.
- **JPA entities for every table**, across `auth/`, `marketplace/`, `content/`, `commerce/`, `creator/`, `viewer/`, `notification/`, all extending or alongside `common/entity/BaseEntity` (which provides audited `createdAt`/`updatedAt`).
- `backend/src/main/resources/graphql/schema.graphqls` — the full MVP 1 GraphQL contract.

**Working — the Auth vertical slice:**

- `auth/resolver/AuthResolver.java` — `register`, `login`, `googleLogin`, `refreshToken`, `logout`, `me`
- `auth/service/AuthService.java` — refresh-token rotation with reuse detection and a 30-second grace window; refresh tokens are opaque UUIDs stored as SHA-256 hashes; BCrypt password hashing
- `auth/security/SecurityConfig.java` — stateless, CSRF off, CORS for `localhost:5173`/`3000`, `@EnableMethodSecurity`, permitAll on `/graphql`, `/graphiql/**`, `/api/auth/**`, `/actuator/health`, `/actuator/info`; **everything else authenticated**
- `auth/security/JwtAuthenticationFilter.java`, `auth/service/SecureLeafUserDetails.java` (authorities are `"ROLE_" + role.name()`)
- Frontend: `pages/auth/LoginPage.tsx`, `pages/auth/RegisterPage.tsx`, `graphql/apolloClient.ts` (with a token-refresh `onError` link), `store/authStore.ts` (Zustand + persist), `hooks/useAuth.ts`, `components/layout/ProtectedRoute.tsx`, `components/layout/AuthLayout.tsx`

**Not started — this is what you are building toward:**

`admin/`, `commerce/`, `content/`, `creator/`, `marketplace/`, `notification/`, `viewer/` contain **nothing but `package-info.java` and `entity/`**. There are **zero repositories, services, controllers or resolvers** outside `auth/`.

MinIO 8.5.10 and PDFBox 3.0.2 are declared in `pom.xml` but **no class imports either one**. `@EnableAsync` is on `SecureLeafApplication` and `processing.thread-pool.*` is configured in `application.yml`, but **no `ThreadPoolTaskExecutor` bean and no `@Async` method exist**. Redis is configured and unused.

**There are zero tests.** `backend/src/test/java/com/secureleaf/` is nine empty directories.

The frontend has only the two auth pages plus a placeholder `HomePage` defined inline inside `App.tsx`.

## 4. Goal of this phase

Ship a **vertical slice** — backend and UI together, demoable end to end:

> A user becomes a Creator, fills in a product form, uploads a PDF, and watches it go `PROCESSING → LIVE` on a real Creator Dashboard, with page tiles and a thumbnail landing in MinIO, driven by a PostgreSQL-backed job queue.

This satisfies the **Upload** and **Creator Dashboard** rows of the MVP 1 scope table (`docs/requirements.md:176-186`) and unblocks every remaining slice — marketplace, commerce, viewer and library all need a LIVE product with tiles to exist, and today there is no way to get a product into the database at all.

**Explicitly out of scope for this phase:** the public marketplace queries (`products`, `product`), commerce, the secure viewer, the buyer library, the admin module, password reset (AUTH-07) and admin suspension (AUTH-08).

---

## 5. TASK A — Fix two confirmed bugs first

### A1. The `me` query is broken (throws `LazyInitializationException`)

`AuthService.getUserById` at `backend/src/main/java/com/secureleaf/auth/service/AuthService.java:207-210` calls `userRepository.findById` — **not** `findWithRolesById` — and is **not** annotated `@Transactional`. `UserMapper.toDto` (`auth/mapper/UserMapper.java:16`) then iterates the lazy `user.getRoles()` collection, while `application.yml` sets `spring.jpa.open-in-view: false`.

This is currently invisible because `frontend/src/hooks/useAuth.ts:37-40` runs `ME_QUERY` with `errorPolicy: 'ignore'`, so session hydration silently fails and the app falls back to the stale persisted user. It will break loudly in this phase, because roles now determine what the UI can do.

**Fix:** use the existing `findWithRolesById` (it already carries `@EntityGraph`) and add `@Transactional(readOnly = true)`.

### A2. Logout never reaches the server

`schema.graphqls:171` declares `logout(refreshToken: String!): Boolean!`, but `frontend/src/graphql/mutations/auth.mutations.ts` sends `mutation Logout { logout }` with no argument. That is a GraphQL validation error, swallowed by the `try/catch` in `useAuth.ts:100-108`. The refresh token is **never revoked server-side** and stays valid for 7 days after the user "signs out".

**Fix:** add the `$refreshToken: String!` variable to the mutation document, pass the token from `useAuthStore`, and route the sign-out button through `useAuth().logout`. The inline `HomePage` button in `App.tsx` currently calls `clearAuth()` and sets `window.location.href` directly, bypassing the hook entirely.

---

## 6. TASK B — Backend: shared infrastructure

### B1. `common/storage/StorageService` (interface)

```java
public interface StorageService {
    void put(String bucket, String key, byte[] content, String contentType);
    byte[] get(String bucket, String key);
    String presignedGetUrl(String bucket, String key, Duration ttl);
    void delete(String bucket, String key);
}
```

**Code it as an interface from day one.** `docs/project_plan.md:50` prescribes exactly this pattern for the future `WatermarkRenderer`. It keeps MinIO out of the pipeline's compile-time surface and — critically — lets the pipeline integration tests run with an in-memory implementation instead of a MinIO container.

- `common/storage/MinioStorageService.java` — the real implementation, `@Service`.
- `common/config/MinioConfig.java` + `MinioProperties` — `@ConfigurationProperties("minio")`, binding the keys **already present** in `application.yml`: `minio.endpoint`, `minio.access-key`, `minio.secret-key`, `minio.bucket.raw`, `minio.bucket.tiles`, `minio.bucket.thumbnails`. Follow the existing `auth/config/JwtProperties.java` pattern (`@Validated`, `@ConfigurationProperties`).

### B2. `common/config/AsyncConfig`

Define the missing `ThreadPoolTaskExecutor` bean named `contentProcessingExecutor`, bound to the already-configured `processing.thread-pool.{core-size, max-size, queue-capacity, thread-name-prefix}` properties (defaults: core 4, max 10, queue 100, prefix `content-proc-`). `@EnableAsync` is already present on `SecureLeafApplication`.

Also add `@EnableScheduling` to `SecureLeafApplication` — the job poller in Task D needs it.

### B3. Repositories

Create these Spring Data interfaces (none exist today):

- `marketplace/repository/`: `ProductRepository`, `CategoryRepository`, `ProductTagRepository`
- `content/repository/`: `DocumentVersionRepository`, `ContentPageRepository`
- `creator/repository/`: `ProcessingJobRepository`, `CreatorProfileRepository`

`ProductRepository` needs `findByCreatorIdAndDeletedAtIsNull(Long creatorId)` for `myProducts`, and `existsBySlug(String slug)` for slug generation.

The one non-trivial method is the job-queue claim, which **must** be safe across concurrent workers:

```java
// ProcessingJobRepository
@Query(value = """
    SELECT * FROM processing_jobs
    WHERE status = 'QUEUED'
    ORDER BY queued_at ASC
    LIMIT :limit
    FOR UPDATE SKIP LOCKED
    """, nativeQuery = true)
List<ProcessingJob> claimQueued(@Param("limit") int limit);
```

`FOR UPDATE SKIP LOCKED` is what makes a PostgreSQL job queue viable instead of Kafka — see the architecture rationale in `docs/project_plan.md:56-62`. The supporting partial index `idx_processing_jobs_status_queued` already exists at `V1__init_schema.sql:268`.

---

## 7. TASK C — Backend: creator role and product management

### C1. `becomeCreator` (requirement AUTH-06)

**This is a hard blocker for the whole phase.** `AuthService.assignDefaultRole` gives every new user — local and Google alike — only `Role.BUYER`, and there is no mutation anywhere in the codebase that grants `CREATOR`. Without this, nobody can upload anything.

Add to `schema.graphqls`:

```graphql
input BecomeCreatorInput {
    bio: String
    payoutEmail: String
    payoutUpi: String
}
```
and under `type Mutation`: `becomeCreator(input: BecomeCreatorInput!): User!`

Implement `AuthService.becomeCreator(userId, bio, payoutEmail, payoutUpi)`:
- **Idempotent** — adds a `UserRole(CREATOR)` row only if absent, and upserts the `CreatorProfile`.
- `CreatorProfile` uses `@MapsId` on a `@OneToOne` to `User`, so set the **`user` reference**, not the raw id.
- Resolver method on `AuthResolver`, annotated `@PreAuthorize("isAuthenticated()")`.

> **Important:** a role change does **not** invalidate the already-issued 15-minute access token. The client must call the `refreshToken` mutation immediately after `becomeCreator` to obtain a JWT carrying the `CREATOR` claim. Handle this on the **frontend** (Task E) — do not build token-invalidation machinery.

### C2. Product DTOs and mapper

Create `marketplace/dto/ProductDto`, `marketplace/dto/CategoryDto`, and `marketplace/mapper/ProductMapper`, mirroring the existing `auth/dto/UserDto` + `auth/mapper/UserMapper` pattern.

**A DTO layer is required, not optional.** The schema's `Product.tags` is `[String!]!` while the entity holds `List<ProductTag>`; and `Product.creator: User!` / `Product.category: Category!` are lazy associations that will throw `LazyInitializationException` outside a transaction, exactly like bug A1. Perform all mapping **inside** the `@Transactional` service boundary.

### C3. `marketplace/service/ProductService`

- `createProduct(CreateProductInput input, Long creatorId)` — creates a `DRAFT` product; derives a slug from the title with a uniqueness suffix; inserts `ProductTag` rows (**tags must be lowercase** — there is a CHECK constraint at `V1__init_schema.sql:111`); validates `pricePaise >= 0` and `freePreviewPages >= 0`.
- `myProducts(Long creatorId)`
- `unpublishProduct(Long id, Long creatorId)` — sets status `UNPUBLISHED`
- `deleteProduct(Long id, Long creatorId)` — **soft delete** via `deletedAt` only (UPLOAD-09); buyers retain their entitlements

**Every mutating method must call a private `assertOwnership(product, creatorId)`** that throws when the product's `creatorId` does not match. This is the object-level authorization requirement at `docs/requirements.md:166` and `docs/project_plan.md:64-66`. `@PreAuthorize("hasRole('CREATOR')")` alone is **not sufficient** — it would let any creator modify any other creator's products.

### C4. `marketplace/resolver/ProductResolver`

Implement the already-declared operations `createProduct`, `myProducts`, `unpublishProduct`, `deleteProduct`, each annotated `@PreAuthorize("hasRole('CREATOR')")`.

**Leave `products` and `product` (the public marketplace queries) unimplemented** — they belong to the next phase.

Errors: use the existing `common/exception/` types (`BusinessException` + `ErrorCode`, `ResourceNotFoundException`, `DuplicateResourceException`). `GlobalGraphQlExceptionHandler` already maps them to typed GraphQL errors with `extensions.code` — do not add a parallel error mechanism.

---

## 8. TASK D — Backend: upload endpoint and processing pipeline

### D1. `content/controller/DocumentUploadController`

`POST /api/products/{productId}/document`, `consumes = multipart/form-data`, `@PreAuthorize("hasRole('CREATOR')")`.

This is the **one** non-GraphQL surface in this phase. `SecurityConfig` already routes everything outside its permit-list through the JWT filter, and `spring.servlet.multipart` limits are already set to 55MB/60MB in `application.yml` — **no security config change is needed**. Do **not** widen the `/api/auth/**` permitAll rule to cover this path.

Flow:
1. Ownership check via `ProductService`.
2. Validate size `<= 50MB` (UPLOAD-02) and verify the **actual PDF magic bytes `%PDF-`**, not the client-supplied content type (UPLOAD-03).
3. `storageService.put(rawBucket, "products/{productId}/v1/{uuid}.pdf", ...)`.
4. Create a `DocumentVersion` v1 — `originalFilename`, `fileSizeBytes`, `rawMinioBucket`, `rawMinioObjectKey`.
5. Insert a `ProcessingJob` with status `QUEUED`; set the product's status to `PROCESSING`.
6. Return `202 Accepted` with `{ documentVersionId, jobId, status }`.

MVP 1 is **one document per product** (`docs/requirements.md:200`) — a re-upload replaces v1 rather than creating v2.

### D2. `content/service/ProcessingJobWorker`

`@Scheduled(fixedDelay = 5000)` polls `claimQueued`, flips each claimed job to `PROCESSING` with `workerId` and `claimedAt`, then hands it off to an `@Async("contentProcessingExecutor")` method.

### D3. `content/service/DocumentProcessingService` — the stage machine

Update `ProcessingJob.currentStage` as it advances. The `JobStage` enum already defines exactly these five values:

| Stage | Work |
|---|---|
| `VALIDATE` | Load from the raw bucket, open with PDFBox, read page count, reject encrypted PDFs |
| `CONVERT_TILES` | `PDFRenderer.renderImageWithDPI(i, 150)` → PNG → `tiles` bucket at `tiles/{versionId}/{pageNo}.png`; insert one `ContentPage` row per page with `widthPx`, `heightPx`, `fileSizeBytes` |
| `GENERATE_THUMBNAIL` | Page 1 scaled to ~400px wide → `thumbnails` bucket; set `documentVersion.thumbnailMinioKey` and `product.coverImageUrl` |
| `GENERATE_PREVIEW` | **No-op for MVP.** The free-preview boundary is enforced at serve time from `product.freePreviewPages`, so nothing needs pre-rendering. Record the stage and advance. |
| `MARK_LIVE` | Set `documentVersion.pageCount` and `processedAt`; product → `LIVE`; job → `COMPLETED` |

**Failure handling (UPLOAD-10):** catch per job, increment `retryCount`; if `retryCount < maxRetries` (3, already the entity default) return the job to `QUEUED` for re-pickup; otherwise set the job to `FAILED` with a `failureReason` and the product to `FAILED`. Write every stage so it is **re-runnable from the start** — tiles overwrite by deterministic key, and `ContentPage` inserts are guarded by the existing `uq_content_pages_version_page` constraint (`V1__init_schema.sql:144`).

> **CRITICAL SECURITY CONSTRAINT:** the tiles written here are **clean — unwatermarked**. Both buckets are created private by the `minio-init` sidecar and **must stay private**. The watermark burn happens per-request in the viewer phase (`docs/requirements.md:88`), never at processing time. Never make a bucket public, and never return a raw MinIO URL to a client.

Log upload and job events with a correlation ID (`docs/requirements.md:171`) — `ProcessingJob.id` is a natural choice.

### D4. Known type note (informational, not a bug to fix)

`schema.graphqls` types `pricePaise` as GraphQL `Int` (32-bit, max ≈ ₹21.4M) while `Product.pricePaise` is a Java `Long`. This is fine at realistic prices. Leave it; it is flagged so it stays a deliberate choice.

---

## 9. TASK E — Frontend

### E1. Infrastructure

- **`src/lib/restClient.ts`** — an axios instance with `baseURL: '/api'` and a request interceptor that attaches `accessToken` from `useAuthStore.getState()`. `axios` is already a dependency and is currently imported nowhere. The Vite dev proxy already forwards `/api` to `localhost:8080` (`vite.config.ts`), as does `infra/nginx/nginx.conf` in production.
- **`src/components/layout/AppLayout.tsx`** — a real header/nav with role-aware links, replacing the placeholder `HomePage` currently defined inline inside `App.tsx`.
- **`ProtectedRoute`** — add an optional `requiredRole?: UserRole` prop that redirects to `/become-creator` when a `CREATOR` route is opened without the role.
- **`.env.example`** at the repo root — `VITE_GRAPHQL_URL`, `VITE_GOOGLE_CLIENT_ID`. **No env file exists anywhere today**, so `VITE_GOOGLE_CLIENT_ID` defaults to `''` in `App.tsx` and Google login cannot work out of the box.

### E2. GraphQL documents

`src/graphql/fragments/product.fragments.ts`, `src/graphql/queries/product.queries.ts` (`MY_PRODUCTS`), `src/graphql/mutations/product.mutations.ts` (`CREATE_PRODUCT`, `UNPUBLISH_PRODUCT`, `DELETE_PRODUCT`, `BECOME_CREATOR`).

### E3. Pages

| Route | File | Behaviour |
|---|---|---|
| `/become-creator` | `pages/creator/BecomeCreatorPage.tsx` | Bio + payout email/UPI → `becomeCreator` → **immediately call `refreshToken`** so the new JWT carries `ROLE_CREATOR` → redirect to the dashboard |
| `/creator` | `pages/creator/CreatorDashboardPage.tsx` | `myProducts` table: thumbnail, title, status badge (`DRAFT`/`PROCESSING`/`LIVE`/`UNPUBLISHED`/`FAILED`), `totalSales`, revenue, unpublish/delete actions, and an "Upload new product" CTA (DASH-01/02/04) |
| `/creator/upload` | `pages/creator/UploadProductPage.tsx` | Two steps: (1) form — title, description, category, tags, `pricePaise`, `freePreviewPages` → `createProduct`; (2) PDF file input → `restClient.post('/products/{id}/document', formData)` with upload progress; then poll `myProducts` every 3s until the status is `LIVE` or `FAILED` |

Add `src/hooks/useCreatorProducts.ts` wrapping these queries and mutations, following the shape of the existing `useAuth.ts`.

### E4. Fix drifted types in `src/types/index.ts`

The planned domain types are already stubbed there but have drifted from the backend schema: `Product.price` must become `pricePaise`, and the `ProductStatus` union is missing `DRAFT`.

### E5. Styling

Tailwind utility classes only. Match the emerald `brand` palette in `tailwind.config.js` and the card/gradient idiom already established in `LoginPage.tsx` and `AuthLayout.tsx`.

> Note a documentation conflict: `CLAUDE.md:25` asks for "glassmorphism, vibrant gradients, micro-animations / premium", while `src/index.css` declares "Clean, minimal, light-palette". **Follow the existing auth pages** so the app stays internally consistent.

---

## 10. TASK F — Tests (write them test-first, but keep them thin)

### Backend

Create `backend/src/test/java/com/secureleaf/support/AbstractIntegrationTest` — `@SpringBootTest` plus a Testcontainers `PostgreSQLContainer` wired with `@ServiceConnection` (Testcontainers 1.19.8 is **already** in `pom.xml`), so Flyway runs the real migrations against a real Postgres.

**Do not start a MinIO container.** Register an in-memory `StorageService` implementation via `@TestConfiguration` — this is the payoff for making `StorageService` an interface in Task B1. Redis is unused this phase.

| Test class | Must cover |
|---|---|
| `AuthServiceIT` | Refresh rotation invalidates the old token; reuse outside the grace window revokes all sessions; `becomeCreator` is idempotent and grants `CREATOR` |
| `MeQueryIT` | Regression test for bug A1 — `me` returns roles without `LazyInitializationException` |
| `ProductServiceIT` | **Object-level authz** — creator B gets access-denied when unpublishing creator A's product; `deleteProduct` only soft-deletes |
| `DocumentUploadIT` | Non-PDF bytes rejected; >50MB rejected; happy path creates a `DocumentVersion` + a `QUEUED` job and flips the product to `PROCESSING` |
| `ProcessingPipelineIT` | A 3-page PDF fixture → 3 `ContentPage` rows, thumbnail key set, product `LIVE`. A corrupt-PDF fixture retries to `maxRetries` then lands `FAILED`/`FAILED` |

**Generate the PDF fixtures programmatically with PDFBox in a `@BeforeAll` — do not commit binary files.**

### Frontend

Add `vitest`, `@testing-library/react` and `jsdom`, plus a `"test"` script — there is **no test tooling at all** today. Keep it to three tests: upload-form validation, dashboard status-badge rendering, and the `ProtectedRoute` role redirect.

### Two broken build/CI items to fix along the way

1. `.github/workflows/frontend-ci.yml` runs `npm run tsc -- --noEmit`, but `package.json` has **no `tsc` script** — the step fails with *"Missing script: tsc"*. Change it to `npx tsc --noEmit`, and add `npm test` and `npm run lint` steps (lint is configured but never runs in CI).
2. `backend/.mvn/` exists but **there is no `mvnw` wrapper script**, despite `README.md:103` instructing `./mvnw spring-boot:run`. Generate it with `mvn wrapper:wrapper`.

---

## 10B. TASK G — Write the learning note (MANDATORY, not optional)

**Read `/AI_RULES.md` before you start coding, and treat this task as part of the definition of done.**

The owner of this repository is using this project to learn backend engineering and to prepare for technical interviews. The code is only half the deliverable. **You must also write `docs/learning-notes/phase-02-upload-pipeline.md`**, starting from `docs/learning-notes/TEMPLATE.md` and filling in every section.

Read `docs/learning-notes/phase-01-auth.md` first — it is the reference for the required depth, tone and structure. Match it.

### The standard

Write for a **motivated beginner who will be interviewed on this code in three months**:

- **Define every term the first time it appears.** Assume no prior knowledge of thread pools, job queues, object storage, or Hibernate's transaction boundaries.
- **Always answer "why", not just "what".** "We use `SKIP LOCKED`" is trivia. "We use `SKIP LOCKED` so ten workers can pull from one queue table without blocking each other or processing the same job twice" is an interview answer.
- **Use an analogy for each hard concept,** then give the precise technical version underneath.
- **Name the alternative that was rejected and why it lost** — for this phase, most importantly: why a PostgreSQL job queue instead of Kafka or RabbitMQ (see `docs/project_plan.md:56-62`).
- **Quote real code from the repo** with `file/path.java:LINE` references. Never invent illustrative examples.
- **Record every bug you hit** — symptom, root cause, fix, lesson.
- **End by naming the design's weakest point** and how you'd fix it.

### Concepts this phase's note must cover

At minimum, each explained from scratch with a "what breaks without it":

| Concept | Must explain |
|---|---|
| Synchronous vs. asynchronous processing | Why upload returns `202 Accepted` immediately instead of holding an HTTP thread for 30 seconds |
| Job queue as a table | Why Postgres beats Kafka at ~10 uploads/day; what changes at 10,000 |
| `FOR UPDATE SKIP LOCKED` | How many workers share one queue safely; what happens without `SKIP LOCKED` (they block) and without `FOR UPDATE` (double processing) |
| Thread pools | What core size, max size and queue capacity actually mean; what happens when the queue fills |
| Retry and idempotency | Why every stage must be safe to re-run; what makes our stages idempotent (deterministic keys + a unique constraint) |
| State machines | Why `QUEUED → PROCESSING → COMPLETED/FAILED` beats a pair of booleans |
| Object storage vs. database | Why PDFs and PNGs go in MinIO and not a `BYTEA` column |
| Interface-driven design | Why `StorageService` is an interface (Strategy pattern) and how that lets tests run without MinIO |
| Object-level authorization | Why `@PreAuthorize("hasRole('CREATOR')")` is *not enough*, what BOLA is, and what `assertOwnership` prevents |
| Magic-byte validation | Why the client's `Content-Type` is not trustworthy |
| DTOs and the N+1 problem | Why entities are never returned from a GraphQL resolver; what lazy loading does outside a transaction |
| Testcontainers | Why integration tests run against real Postgres instead of H2 |

### Also update

- `docs/learning-notes/README.md` — mark Phase 2 done in the index table
- `docs/learning-notes/interview-prep/glossary.md` — add every new term
- `docs/learning-notes/interview-prep/quick-reference.md` — fill in the Phase 2 section (it is currently stubbed with placeholders)

---

## 11. Acceptance criteria — how to verify you are done

**1. Infrastructure**
```bash
cd infra && docker compose up -d
docker compose ps    # postgres, redis, minio healthy; minio-init exited 0
```

**2. Backend**
```bash
cd backend && ./mvnw verify          # all integration tests green
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```
Flyway must apply `V1` and `V2` cleanly against a fresh volume, and `ddl-auto: validate` must pass. **A validation failure means an entity drifted from the migration — fix the entity, not the migration.**

**3. End-to-end through the UI** (`cd frontend && npm run dev`, then http://localhost:5173)

Register → sign in → `/become-creator` → `/creator/upload`: fill the form and upload a real multi-page PDF. The dashboard row must move `PROCESSING → LIVE` within seconds **without a manual page refresh**.

**4. Confirm the artifacts landed** — MinIO console at http://localhost:9001 (`secureleaf_minio_user` / `secureleaf_minio_pass`):
- `secureleaf-raw` holds the original PDF
- `secureleaf-tiles` holds one PNG per page
- **both buckets still report access policy `none`** — no public read (`docs/requirements.md:162`)

**5. Database** (pgAdmin at http://localhost:5050, or psql):
```sql
SELECT status, current_stage, retry_count, failure_reason FROM processing_jobs;
SELECT page_number, minio_object_key, width_px FROM content_pages ORDER BY page_number;
SELECT status, page_count FROM document_versions;
```

**6. Security spot-checks**
- `curl -F file=@x.pdf localhost:8080/api/products/1/document` with **no** Authorization header → **401**
- Upload to a product owned by a different creator → **access denied**
- Upload a `.png` renamed to `.pdf` → **rejected on magic bytes**
- In GraphiQL (http://localhost:8080/graphiql) as a `BUYER`-only user: `createProduct` → **access denied**; `me` → returns `roles` **without error**

**7. Failure path** — upload a deliberately corrupt PDF. Watch `processing_jobs.retry_count` climb to 3, then the job land `FAILED` with a populated `failure_reason`, and the product show `FAILED` on the dashboard.

---

**8. The learning note exists and is complete** (Task G) — `docs/learning-notes/phase-02-upload-pipeline.md` covers every concept in the table above, quotes real code with file:line references, records the bugs you hit, and includes beginner/intermediate/advanced interview Q&A. `README.md`, `glossary.md` and `quick-reference.md` are updated.

> **The phase is not done until item 8 is done.** Code that works with no note is a half-finished phase.

---

## 12. What comes after this phase (context only — do not build)

| Phase | Slice | Delivers |
|---|---|---|
| 3 | Marketplace | Public `products`/`product` queries, full-text search via the existing GIN index, category/price filters, sort, pagination; marketplace grid + product detail pages |
| 4 | Commerce | `initiateOrder` / `confirmMockPayment`, orders → payments → entitlements with `idempotency_key`, immutable `payment_events`, 10% commission |
| 5 | Secure Viewer ⭐ | `WatermarkRenderer` Strategy + Java2D burn, 30-second signed tile URLs, Redis `SET NX` single-session enforcement, heartbeats, `viewer_access_logs`, Canvas viewer with anti-piracy controls |
| 6 | Library + polish | `myLibrary`, reviews, Redis Pub/Sub notifications, AUTH-07 password reset |

Design decisions made in this phase — the `StorageService` interface, the job-queue claim pattern, the DTO/mapper layer, and `assertOwnership` — are the foundations those phases build on. Keep them clean.