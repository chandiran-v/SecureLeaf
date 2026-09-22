# Phase 3 — Marketplace (with watermarked free preview)

> Implementation prompt / plan. Hand this to the implementing agent as-is.

## Context

SecureLeaf has shipped Phase 0 (schema), Phase 1 (auth/JWT) and Phase 2 (creator profile + PDF
upload → async tile pipeline). A creator can register, create a product, upload a PDF, and watch the
`ProcessingJobWorker` pipeline flip it to `LIVE`. **But nobody can see it.** `App.tsx` renders a
hardcoded `MarketplacePlaceholder` ("Product listings are coming in Phase 3") behind a
`ProtectedRoute`, and the GraphQL schema declares `products` / `product` with no resolver behind them.
Content goes in; nothing comes out.

Phase 3 closes that loop: public, unauthenticated browsing of `LIVE` products — full-text search,
filters, sorting, pagination, a product detail page, and a watermarked free preview of the first N
pages. Commerce (Phase 4) and the full DRM viewer (Phase 5) come after; this phase ends with a
visitor able to *find* and *sample* a product, with a disabled Buy button as the seam for Phase 4.

Per the project's own phase index (`docs/learning-notes/README.md`), the headline teaching topics are
**full-text search, pagination, N+1 queries, and DTO projection**.

### Decisions taken
- **Full-stack** — backend queries *and* the React browsing UI, matching how Phases 1–2 shipped.
- **Free preview included now.** This pulls one piece of Phase 5 forward: preview tiles must be
  **watermarked**. Tiles are currently stored clean, so serving them raw would ship the exact leak
  the DRM design exists to prevent. See D5 — this is the single biggest deviation from a
  browse-only Phase 3, and it buys us the `WatermarkRenderer` Strategy that Phase 5 reuses.
- **Extra-depth learning note** on query performance (`EXPLAIN ANALYZE`, measured N+1 before/after,
  Specification vs native SQL vs QueryDSL).

---

## Verified starting facts

| Fact | Evidence |
|---|---|
| FTS index already exists — **no migration needed** | `V1__init_schema.sql:107` — `GIN (to_tsvector('english', title \|\| ' ' \|\| description))` |
| Listing index already exists | `V1__init_schema.sql:106` — partial index on `(status, created_at DESC)` |
| All 20 tables already created | `V1__init_schema.sql`; V2 fixes `ip_address`, V3 seeds categories |
| `/graphql` is **already** `permitAll` — no SecurityConfig change | `SecurityConfig.java:51` |
| The real auth gate is the frontend | `App.tsx:42,50` wrap `/` and `/marketplace` in `ProtectedRoute` |
| `UserDto` leaks `email` | `UserDto.java` — `record UserDto(Long id, String email, ...)` |
| `presignedGetUrl` exists but is unused | `common/storage/StorageService.java` |
| `Product.coverImageUrl` holds a **MinIO key**, not a URL | set in `DocumentProcessingService` GENERATE_THUMBNAIL |
| Preview tiles are reachable | `ContentPage` has `bucketName` + `minioObjectKey`; `ContentPageRepository.findByDocumentVersionIdOrderByPageNumber` |
| Phase 2 wrongly marked "Not started" | `docs/learning-notes/README.md` index row 2 |

---

## Design decisions

### D1 — Query construction: custom repository fragment with **native SQL**
New `ProductSearchRepository` + `ProductSearchRepositoryImpl` using `EntityManager.createNativeQuery`,
assembling the `WHERE` clause dynamically with **named parameters only**.

The FTS predicate must be written verbatim:
```sql
to_tsvector('english', title || ' ' || description) @@ plainto_tsquery('english', :q)
```
`idx_products_fts` is an **expression index** — it only fires if the query expression matches exactly.

*Rejected — Criteria/Specifications:* can emit this via `cb.function(...)`, but the builder chain is
unreadable and may parenthesize/cast the expression so it silently stops matching the index. For a
repo whose purpose is teaching, hiding the SQL destroys the lesson. *Rejected — QueryDSL:*
annotation-processor codegen for one query surface.

- `sortBy` is a **whitelist switch**, never interpolated: `newest`→`created_at DESC`,
  `popular`→`total_sales DESC`, `rating`→`average_rating DESC NULLS LAST`, `price_asc|price_desc`.
- When `searchQuery` is present and no explicit sort: order by `ts_rank(...) DESC, created_at DESC`.
- Base predicate, always applied and **not optional**: `status = 'LIVE' AND deleted_at IS NULL`.

### D2 — N+1 + pagination: **two-step ID paging** (flagship teaching point)
`@EntityGraph` + `Pageable` over the `tags` collection triggers Hibernate `HHH000104:
firstResult/maxResults specified with collection fetch; applying in memory` — it silently loads the
whole table and paginates in JVM memory. Correct approach, **exactly 3 SQL statements regardless of
page size**:

1. Native query selecting `p.id` only, with `LIMIT/OFFSET` (no collection join → Postgres paginates).
2. Sibling `COUNT(*)` with identical `WHERE`.
3. `@EntityGraph({"creator","category","tags"}) List<Product> findAllByIdIn(List<Long> ids)` — one
   query, no limit, so Hibernate is happy.

Then **re-order in Java** to the step-1 id order via a `Map<Long,Product>` — the JOIN's row order is
not the `ORDER BY` order. This is easy to get wrong and is worth a javadoc note.

### D3 — `thumbnailUrl`: GraphQL **field resolver**
Rename the `ProductDto` component `thumbnailUrl` → `thumbnailKey`, and add
`@SchemaMapping(typeName="Product", field="thumbnailUrl")` in a new `ProductFieldResolver` injecting
`StorageService`, returning `presignedGetUrl(thumbnails, key, Duration.ofHours(1))`, null-safe.

Computed only when the client selects the field; keeps `ProductMapper` a dependency-free static
utility (preserving its documented transactional-boundary rationale); no MinIO key reaches the wire.
**TTL 1h** for public thumbnails vs the **30s** Phase 5 uses for DRM tiles — contrast in the note.
Frontend caveat: the URL differs per request, so never use it as a React `key`.

### D4 — Public DTO safety
Add `CreatorSummaryDto(Long id, String displayName)` and schema `type CreatorSummary`; change
`Product.creator: User!` → `CreatorSummary!`. **This ripples** into
`frontend/src/graphql/fragments/product.fragments.ts`, where `PRODUCT_FIELDS` currently spreads
`USER_FIELDS` — the creator dashboard must be updated in the same commit.

LIVE/not-deleted is enforced **in SQL**, not in the resolver, so no code path can bypass it.
`product(id)` on a DRAFT/UNPUBLISHED/deleted product throws `ResourceNotFoundException` — do not
return 403, which would leak existence.

### D5 — Free preview: **watermarked, streamed through the backend**
Because preview ships now, clean tiles must never reach the browser. Two rules:
- **No presigned URLs for the tiles bucket.** Presigning a clean tile *is* the leak. Thumbnails
  (D3) are presigned because they are already public marketing assets; page tiles are not.
- Preview bytes are streamed from a backend endpoint that watermarks on the way out.

New public REST endpoint:
```
GET /api/products/{productId}/preview/{pageNumber}   →  image/png
```
Validation, in order: product is `LIVE` and not soft-deleted → `1 <= pageNumber <=
min(product.freePreviewPages, documentVersion.pageCount)` → load `ContentPage` → fetch clean bytes
via `StorageService.get` → watermark → stream. Anything out of range is a 404, not a 403.

Introduce the **`WatermarkRenderer` Strategy interface** that `docs/project_plan.md` (decision #1)
already calls for, with a `Java2DWatermarkRenderer` implementation. Preview has no buyer identity to
burn in, so it renders a static diagonal `"PREVIEW · SecureLeaf"`. Phase 5 reuses the same interface
with the buyer's email/id — the DRM work gets its scaffolding a phase early, at no extra cost.

Watermarked preview bytes are deterministic per page, so cache them in the tiles bucket under a
`preview/` key prefix on first render (read-through). Optional; skip if it complicates the step.

*Note:* this endpoint is public and does CPU work per request, so it is a DoS surface. Phase 3 caps
it with the page-range check and the read-through cache; real rate limiting is on the MVP-2 list.

### D6 — Frontend state: URL params, page-number pagination
`useSearchParams` is the single source of truth (`q`, `category`, `sort`, `page`, `minPrice`,
`maxPrice`, `free`) → shareable, back-button-safe, and Apollo's cache key already varies by
variables. No Zustand (the auth store stays auth-only). The schema is offset-based (`ProductPage`),
so numbered pagination, not `fetchMore`/relay. Search debounced 350 ms via a new `useDebouncedValue`
hook, writing to the URL with `replace: true` so typing doesn't spam history.

---

## File plan

**Backend — new**
- `marketplace/dto/` — `CreatorSummaryDto.java`, `ProductFilterInput.java`, `ProductPageDto.java`
- `marketplace/repository/` — `ProductSearchRepository.java`, `ProductSearchRepositoryImpl.java`
- `marketplace/resolver/` — `ProductFieldResolver.java`, `CategoryResolver.java`
- `marketplace/service/ProductSearchService.java` (keeps the already-long `ProductService` from growing)
- `content/watermark/` — `WatermarkRenderer.java` (interface), `Java2DWatermarkRenderer.java`
- `content/controller/PreviewController.java`, `content/service/PreviewService.java`

**Backend — modified**
- `marketplace/repository/ProductRepository.java` — add `findAllByIdIn` (`@EntityGraph`),
  `findLiveById`; extend `ProductSearchRepository`
- `marketplace/resolver/ProductResolver.java` — add public `products(filter, page, size)` and
  `product(id)` with **no** `@PreAuthorize`; delete the "belongs to Phase 3" javadoc note
- `marketplace/mapper/ProductMapper.java` — emit `CreatorSummaryDto` + `thumbnailKey`; add `toPageDto`
  (`totalElements` needs `Math.toIntExact` — schema says `Int!`)
- `marketplace/dto/ProductDto.java` — `UserDto creator` → `CreatorSummaryDto creator`;
  `thumbnailUrl` → `thumbnailKey`
- `resources/graphql/schema.graphqls` — add `type CreatorSummary`, change `Product.creator`,
  add `categories: [Category!]!`, add `Product.pageCount`
- `content/repository/ContentPageRepository.java` — add lookup by version + page number

**Frontend — new**
- `pages/marketplace/MarketplacePage.tsx`, `pages/marketplace/ProductDetailPage.tsx`
- `components/marketplace/` — `ProductCard`, `ProductGrid`, `ProductFilters`, `SearchBar`,
  `Pagination`, `ProductCardSkeleton`, `EmptyState`, `ErrorState`, `PreviewPane`
- `hooks/useProductSearch.ts`, `hooks/useDebouncedValue.ts`, `lib/formatPrice.ts` (paise → ₹)
- `graphql/queries/marketplace.queries.ts` — `PRODUCTS`, `PRODUCT_DETAIL`, `CATEGORIES`

**Frontend — modified**
- `App.tsx` — delete `MarketplacePlaceholder`; make `/` and `/marketplace` public (drop
  `ProtectedRoute` at lines 42, 50); add `/product/:id`
- `graphql/fragments/product.fragments.ts` — add `PRODUCT_CARD_FIELDS` (no description) for the grid;
  fix `PRODUCT_FIELDS` creator selection for the `CreatorSummary` change
- `types/index.ts` — `Product.creator: CreatorSummary`; add the sort union type
- `components/layout/AppLayout.tsx` — nav must render for logged-out visitors

`PreviewPane` draws the streamed PNG onto an HTML5 `<canvas>` (not `<img>`), with right-click,
drag and text-select disabled — the same posture the Phase 5 viewer will use, so the component
is a rehearsal for it.

---

## Commit breakdown

1. Schema + DTOs (`CreatorSummary`, filter input, page DTO) — compiles, no behaviour change
2. `ProductSearchRepositoryImpl` two-step paging + repo methods
3. `ProductSearchService` + public `products`/`product` resolvers + `categories`
4. `ProductFieldResolver` presigned thumbnails
5. `WatermarkRenderer` Strategy + `Java2DWatermarkRenderer`
6. `PreviewController`/`PreviewService` + page-range enforcement
7. `MarketplaceQueryIT` + `PreviewControllerIT`
8. Frontend Apollo ops + types + fragment fixes
9. `MarketplacePage` + components + routing (drop `ProtectedRoute`)
10. `ProductDetailPage` + `PreviewPane` + disabled Buy stub
11. Vitest tests
12. Learning notes + README/glossary/quick-reference

---

## Tests

`backend/src/test/java/com/secureleaf/marketplace/MarketplaceQueryIT.java` — extends the existing
`AbstractIntegrationTest` (Testcontainers Postgres), `HttpGraphQlTester` **unauthenticated**:
- FTS returns matches, excludes non-matches; `EXPLAIN ANALYZE` assertion that the plan contains
  `idx_products_fts` (seed enough rows, or `SET enable_seqscan=off`, so the planner picks it)
- category + maxPrice + isFree combine with AND semantics
- DRAFT, UNPUBLISHED and soft-deleted excluded from both `products` and `product(id)`
- pagination: 25 LIVE products, size 10 → `totalElements=25, totalPages=3, pageNumber=1`, 5 on last page
- **N+1 assertion**: Hibernate `Statistics.getPrepareStatementCount() == 3` for a 20-row page with
  tags selected (enable `hibernate.generate_statistics` in `application-test.yml`)
- `thumbnailUrl` is a presigned URL, never a raw key; `creator` cannot select `email` (schema-level)
- ordering: `price_asc`, `rating` NULLS LAST, relevance when `searchQuery` set

`PreviewControllerIT` — uses the existing `InMemoryStorageService` double:
- page within `freePreviewPages` returns 200 `image/png`
- page **beyond** `freePreviewPages` returns 404 (the core DRM boundary test)
- preview of a non-LIVE or soft-deleted product returns 404
- returned bytes differ from the stored clean tile bytes (proves the watermark was applied)

Frontend Vitest (`MockedProvider`, existing pattern): grid renders from mocked `PRODUCTS`; skeleton →
data transition; empty state; error state; typing updates `?q=` after debounce; page 2 sets `?page=1`
and refetches; detail page shows a disabled Buy with a "Coming in Phase 4" title.

---

## Learning notes (phase gate — per `AI_RULES.md` this phase is half-finished without it)

- **`docs/learning-notes/phase-03-marketplace.md`** from `TEMPLATE.md`, at the depth of phases 1–2,
  plus the requested query-performance deep dive:
  - GIN expression indexes; why the predicate must match verbatim; `plainto_tsquery` vs `to_tsquery`;
    `ts_rank` relevance ordering
  - **Pasted `EXPLAIN ANALYZE` output** showing `Bitmap Index Scan on idx_products_fts`, with the
    seq-scan plan alongside for contrast
  - `HHH000104` and the three-query paging recipe; **measured** statement counts before/after
  - Specification vs native SQL vs QueryDSL compared in detail, with the rejection reasoning
  - DTO projection + field resolvers for lazy/expensive fields; offset vs keyset pagination trade-off
  - Watermark Strategy pattern and why preview must not be presigned
- **`docs/learning-notes/README.md`** — fix the index row that wrongly marks Phase 2 "Not started";
  mark Phase 3 done and link it
- **`interview-prep/glossary.md`** — GIN index, tsvector/tsquery, ts_rank, N+1, entity graph, offset
  vs keyset pagination, presigned URL TTL, Strategy pattern
- **`interview-prep/quick-reference.md`** — the exact FTS predicate, the 3-query paging recipe, the
  `@SchemaMapping` snippet
- **`README.md`** — marketplace + preview in the feature list

---

## Verification (end-to-end)

1. `docker compose -f infra/docker-compose.yml up -d` (Postgres, Redis, MinIO)
2. `cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`
3. Seed: register a creator, create ~25 products across categories, upload PDFs, wait for
   `ProcessingJobWorker` to flip them `LIVE`
4. In GraphiQL (`http://localhost:8080/graphiql`) **with no Authorization header**, run
   `products(filter:{searchQuery:"..."})` → expect data, not `UNAUTHENTICATED`
5. In `psql`, `EXPLAIN ANALYZE` the generated SQL → confirm `Bitmap Index Scan on idx_products_fts`.
   With `spring.jpa.show-sql=true`, confirm **3 statements** per listing request
6. `curl -o p1.png localhost:8080/api/products/1/preview/1` → a watermarked PNG;
   `curl -i localhost:8080/api/products/1/preview/99` → **404**
7. `cd frontend && npm run dev`, open `/marketplace` in a **private window (logged out)**: grid with
   thumbnails; type a query (URL updates after debounce); change category/sort; go to page 2; paste
   the URL into a new tab → identical results; back button restores prior filters; click a card →
   `/product/:id` showing creator display name (**confirm no email in the Network tab**), tags,
   rating, price, canvas preview limited to the free page count, disabled Buy
8. `./mvnw verify` and `npm run test` both green

---

## Out of scope (deferred, deliberately)

- Orders, payments, entitlements — **Phase 4**
- Full DRM viewer, buyer-identity watermarking, signed 30s tile URLs, Redis single-session
  enforcement — **Phase 5** (this phase only lays the `WatermarkRenderer` Strategy)
- Reviews submission, notifications, admin panel, `myLibrary` — **Phase 6**
- Rate limiting on the preview endpoint — MVP 2

---

## Two things the implementer must not miss

1. The `CreatorSummary` schema change **breaks** the existing `PRODUCT_FIELDS` fragment — the creator
   dashboard must be fixed in the same commit, or the dashboard silently stops rendering creators.
2. The preview endpoint is the one place where getting it wrong leaks clean tiles.
   `PreviewControllerIT`'s page-range test is non-negotiable.
