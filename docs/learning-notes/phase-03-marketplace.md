# Phase 03 — Marketplace (with watermarked free preview)

> **Status:** Done
> **Built:** 2026-09-22
> **Requirement IDs covered:** *(marketplace browsing/search/preview — see `docs/requirements.md`; also closes the "Phase 2 wrongly marked Not started" doc bug)*
> **Commits:** Pending

---

## 1. What we built, in plain English

Before this phase, a creator could sign up, upload a PDF, and watch it become `LIVE` — but nobody could ever see it. The homepage was a placeholder that said "coming in Phase 3," and it was hidden behind a login wall besides.

This phase opens the front door. Anyone — logged in or not — can now visit `/marketplace`, search and filter products by category, price, and free-or-paid, sort them, page through the results, and click into a product's detail page. On the detail page they can flip through a handful of free preview pages before deciding to buy (buying itself is Phase 4 — the button is there, but disabled).

The one twist we pulled forward from later phases: the free preview pages are **watermarked** on the server before they're sent to the browser. The processing pipeline (Phase 2) already stores a clean, unprotected PNG for every page of every document. If we served those clean files directly for "free preview," we'd be handing out exactly the thing the whole DRM system exists to prevent. So this phase also builds the first piece of that protection: a `WatermarkRenderer` that stamps "PREVIEW · SecureLeaf" across a page image before it ever leaves the server.

**Before this phase:** Products existed in the database but were invisible — no query could read them back except the creator's own dashboard.
**After this phase:** Anyone can browse, search, filter, and sample every `LIVE` product; a creator's private (`DRAFT`/`UNPUBLISHED`/deleted) products stay completely invisible to everyone else, enforced in SQL, not in application code.

---

## 2. Why it matters

A marketplace with no way to browse its own inventory isn't a marketplace — Phase 3 is the point where SecureLeaf becomes demoable end-to-end: upload → process → browse → sample. It's also where three of the hardest, most commonly-interviewed backend problems show up for real: **full-text search** (how do you search text without a search engine?), **the N+1 query problem** (how do you paginate a list *and* eager-load its related collections without either exploding the query count or paginating in application memory?), and **DTO projection with lazy fields** (how do you avoid computing an expensive field — a presigned URL — for every row when most callers won't even ask for it?).

Pulling the watermark forward from Phase 5 also matters for a subtler reason: it forces the "never leak a clean tile" rule to exist from the very first moment *any* tile reaches a browser, rather than retrofitting it later once a leak-shaped bug might already be shipped.

---

## 3. New concepts introduced

### 3.1 Full-text search with PostgreSQL `tsvector`/`tsquery`

**What it is:** PostgreSQL's built-in text search. `to_tsvector('english', text)` turns a string into a normalized, searchable document (lowercased, stripped of stopwords like "the"/"a", stemmed so "running" and "run" match). `plainto_tsquery('english', userInput)` does the same normalization to the search box's text. The `@@` operator asks "does this document match this query?"

**The analogy:** A plain `LIKE '%word%'` is like scanning every page of a book by eye, one page at a time. `tsvector`/`tsquery` is the book's index at the back — built once, ahead of time, so a lookup is a jump straight to the matching pages.

**Why we needed it here:** The "search titles and descriptions" box needs to match `"dragon"` against a description containing `"...dragons roam..."` (stemming), ignore case, and ideally rank better matches higher — none of which `LIKE` does, and all of which `tsvector` does for free.

**How it works:**
1. A **GIN index** (Generalized Inverted Index) was already created in `V1__init_schema.sql:107` on the *expression* `to_tsvector('english', title || ' ' || description)` — not on a column. Postgres builds this index once and updates it incrementally as rows change.
2. A query is only served by that index if it contains **the exact same expression** — same function, same config (`'english'`), same concatenation. Anything else (even a semantically-equivalent rewrite) makes Postgres fall back to computing `to_tsvector` fresh for every row — a sequential scan.
3. `ts_rank(document, query)` scores how well a document matches, so results can be ordered by relevance, not just filtered.

**In our code:** `backend/src/main/java/com/secureleaf/marketplace/repository/ProductSearchRepositoryImpl.java:66-67`
```java
private static final String FTS_PREDICATE =
        "to_tsvector('english', p.title || ' ' || p.description) @@ plainto_tsquery('english', :q)";
```

**What breaks without it:** Without the matching-expression discipline, the query still returns *correct* results (Postgres will compute the expression on the fly) but silently stops using the index — a full table scan on every search keystroke. At 25 rows in dev, nobody would ever notice. At 250,000 rows in production, every search becomes a multi-second query with no error, no warning, just a slow marketplace. This is exactly the kind of bug that's invisible in a demo and painful in production — see `EXPLAIN ANALYZE` proof below.

### 3.2 The N+1 query problem and Hibernate's `HHH000104` warning

**What it is:** N+1 is what happens when code fetches a list of N parent rows with one query, then triggers one *additional* query per row to fetch a related collection — N+1 total queries instead of 2. Hibernate has a specific, sharper version of this trap: combining a **paginated query** (`LIMIT`/`OFFSET`) with an **eager-fetched collection join** (`@EntityGraph` on a `@OneToMany`) in the same query.

**The analogy:** Imagine asking a librarian for "page 2 of the fiction catalog, 20 books per page, with each book's list of co-authors." If the librarian joins the co-authors table before paginating, the join can multiply one book into several rows (one per co-author) — so "the next 20 rows" no longer means "the next 20 books." The only way for the librarian to give you a correct page 2 is to pull *every* fiction book, do the join in their head, and then count out 20 books by hand. That's `HHH000104`: Hibernate detects this exact impossibility and falls back to loading the whole table into the JVM and paginating there.

**Why we needed it here:** `products(filter, page, size)` needs to return `Product.tags` (a `@OneToMany`) alongside a correctly paginated page. Doing this the naive way (`@EntityGraph(attributePaths = "tags") Page<Product> findAll(Specification, Pageable)`) triggers exactly this trap.

**How it works — the fix, "two-step ID paging":**
1. `ProductSearchRepositoryImpl.fetchIds()` — a native query selecting **only `p.id`**, with `LIMIT`/`OFFSET`. No collection join, so Postgres itself paginates correctly using the index.
2. `ProductSearchRepositoryImpl.fetchCount()` — a sibling `COUNT(*)` with the identical `WHERE` clause, for `totalElements`.
3. `ProductRepository.findAllByIdIn(ids)` — **one** `@EntityGraph({"creator","category","tags"})` query, no `LIMIT`, fetching the full entities for exactly those ids. No `firstResult`/`maxResults` involved, so Hibernate is happy joining the tags collection.
4. `ProductSearchService.reorder()` — step 3's `JOIN` does **not** preserve step 1's `ORDER BY` (the join's row order follows the query plan, not the ranking we asked for). The result is re-ordered in Java via a `Map<Long, Product>` keyed by id, walked in the order from step 1. **This is the easiest part to get wrong** — skip it and page 1 of a relevance-ranked search silently comes back shuffled.

**In our code:** `backend/src/main/java/com/secureleaf/marketplace/repository/ProductSearchRepositoryImpl.java`, `backend/src/main/java/com/secureleaf/marketplace/service/ProductSearchService.java:44-60`

**What breaks without it:** Silent, not loud. The query still returns *correct data* for page 1 of a small table — which is exactly why this bug survives code review and a quick manual test. It only shows up as "why did fetching page 3 of the marketplace take 4 seconds and use 200MB" once the `products` table has real volume, and by then it's in production. We proved the fix in `MarketplaceQueryIT.listingRequest_executesExactlyThreeStatements_regardlessOfPageSize` — see §6 below for the measured numbers.

### 3.3 DTO projection + `@SchemaMapping` field resolvers for lazy/expensive fields

**What it is:** A GraphQL field resolver is a method that computes exactly one field of a type, and only runs when a client's query actually selects that field. Spring for GraphQL wires this up via `@SchemaMapping(typeName = "...", field = "...")`.

**The analogy:** A restaurant menu doesn't cook every dish in the kitchen up front "just in case" — it cooks what's ordered. A field resolver is the same idea for API responses: `Product.thumbnailUrl` is only computed (a network call to MinIO to sign a URL) if a client's query actually asks for `thumbnailUrl`.

**Why we needed it here:** `ProductMapper` is a static, dependency-free utility on purpose (its own javadoc says so — it makes "must be called inside a transaction" visible at the call site). Presigning a thumbnail URL needs `StorageService`, a Spring bean. Rather than break that contract, the raw MinIO key (`ProductDto.thumbnailKey`) is mapped statically, and a separate `@Controller` (`ProductFieldResolver`) turns it into a signed URL — with dependency injection available — only when a client selects `thumbnailUrl`.

**How it works:**
1. `ProductMapper.toDto()` copies `product.getCoverImageUrl()` (actually a MinIO *object key*, not a URL — the field name is legacy) into `ProductDto.thumbnailKey`.
2. `ProductFieldResolver.thumbnailUrl(ProductDto product)` is annotated `@SchemaMapping(typeName = "Product", field = "thumbnailUrl")`. Spring for GraphQL only invokes it for a request whose GraphQL selection set includes `thumbnailUrl`.
3. It calls `StorageService.presignedGetUrl(thumbnails-bucket, key, Duration.ofHours(1))`.

**In our code:** `backend/src/main/java/com/secureleaf/marketplace/resolver/ProductFieldResolver.java`

**What breaks without it:** Two things, either one bad. Do it eagerly in `ProductMapper` and you either (a) break the "static utility, no DI" contract, or (b) presign a URL for every product on every list query even when the client only asked for `title`/`price` — wasted MinIO calls at list-query volume. Skip signing entirely and return the raw key, and the raw MinIO object key/URL structure leaks to the client — a minor infra-exposure smell, and worse, it's the same mistake that would be catastrophic one bucket over (see §3.4).

### 3.4 Why the *tiles* bucket is never presigned, but the *thumbnails* bucket is (the core DRM boundary)

**What it is:** Two buckets, two trust levels. `secureleaf-thumbnails` holds public marketing images (a book cover) — presigning them is fine, the content itself isn't secret. `secureleaf-tiles` holds the actual page images of paid content — presigning *those* would hand a browser a direct, if temporary, URL to the clean file, defeating the entire reason tiles exist instead of a raw PDF.

**The analogy:** A bookstore hands out a poster of the cover for free — that's the thumbnail. It does not hand out a temporary key to the storeroom just because someone asked to "preview" a page — that's the tile.

**Why we needed it here:** The free-preview feature needs to show real page content to an anonymous visitor. The tempting shortcut — "just presign the first 3 tiles like we do thumbnails" — is the single biggest way this phase could accidentally leak the exact thing SecureLeaf's DRM exists to prevent.

**How it works:** `PreviewService.getPreviewPage()` never calls `presignedGetUrl` on the tiles bucket. It calls `StorageService.get()` (fetches the bytes, server-side), runs them through `WatermarkRenderer.applyWatermark()`, and streams the *result* — never the original bytes — back through `PreviewController`. There is no code path in `PreviewService` that returns storage bytes unmodified.

**In our code:** `backend/src/main/java/com/secureleaf/content/service/PreviewService.java`

**What breaks without it:** A single line of "just presign it like thumbnails" turns the free-preview feature into a way to download full-resolution, watermark-free page images for any `LIVE` product's first N pages, permanently, with zero authentication. This is the finding `PreviewControllerIT.previewBytes_differFromStoredCleanTile_provingWatermarkWasApplied` exists to catch.

### 3.5 The Strategy pattern, applied to watermarking

**What it is:** The Strategy pattern defines a family of interchangeable algorithms behind one interface, so the code that *uses* the algorithm doesn't need to know which concrete implementation it's running.

**The analogy:** A universal phone charger port — the wall socket (`PreviewService`) doesn't care whether the adapter plugged into it (`WatermarkRenderer`) is USB-C or Lightning; it just needs *some* adapter conforming to the port's shape.

**Why we needed it here:** Phase 3's preview watermark and Phase 5's DRM-viewer watermark are the same *operation* (burn text into an image) with different *inputs* (a static label vs. the buyer's email/id) and, potentially, different *implementations* down the line (Java2D now, maybe a native image library later for throughput). Coding this as an interface from day one means Phase 5 injects a different label into the same interface — no refactor of a hardcoded method required.

**How it works:** `WatermarkRenderer.applyWatermark(byte[] imageBytes, String label)` is the interface. `Java2DWatermarkRenderer` is the only implementation today, using the JDK's own `java.awt.Graphics2D` to draw a translucent, diagonal, tiled label across the image — no extra native dependency.

**In our code:** `backend/src/main/java/com/secureleaf/content/watermark/WatermarkRenderer.java`, `Java2DWatermarkRenderer.java`

**What breaks without it:** Nothing breaks *today* — Phase 3 only has one caller and one implementation, so a plain static method would technically work. The cost shows up in Phase 5: without the interface, adding buyer-identity watermarking means either duplicating the Java2D drawing code or refactoring a static method call in every caller under time pressure. Building the seam now is cheap; building it later is a mid-project refactor.

---

## 4. Best practices applied

| Practice | What we did | Why it matters | Where |
|---|---|---|---|
| **Visibility enforced in SQL, not application code** | `WHERE p.status = 'LIVE' AND p.deleted_at IS NULL` is baked into every marketplace query — there's no `if (product.isLive())` filter anywhere in Java. | A missed `if` check is one bad refactor away from leaking a draft product; a SQL predicate that's *always* part of the query can't be accidentally skipped by a new code path. | `ProductSearchRepositoryImpl.java`, `ProductRepository.findByIdAndStatusAndDeletedAtIsNull` |
| **404, never 403, for hidden resources** | A DRAFT/UNPUBLISHED/deleted product, and an out-of-range preview page, both return "not found," never "forbidden." | A 403 confirms the resource *exists but is hidden* — an information leak an attacker can use to enumerate private products. A 404 is indistinguishable from "never existed." | `ProductSearchService.getLiveProduct`, `PreviewService.getPreviewPage` |
| **Narrow public DTOs instead of trusting resolver discipline** | `CreatorSummaryDto(id, displayName)` replaces `UserDto` on `Product.creator` — the email field doesn't exist on the type at all. | Makes a PII leak a *compile-time impossibility*, not a "please remember not to select that field" convention that erodes over time. | `CreatorSummaryDto.java`, `schema.graphqls` |
| **Whitelist, never interpolate, dynamic SQL fragments** | `sortBy` is matched against a fixed `switch` of five known values before it ever touches the SQL string; nothing from the input is concatenated in raw. | Hand-built SQL strings (chosen deliberately here for the FTS predicate, see §3.1) are a SQL-injection risk the moment *any* piece of them comes from user input unfiltered. A switch statement closes that door entirely. | `ProductSearchRepositoryImpl.resolveOrderBy()` |
| **DTOs decouple wire format from storage format** | `ProductDto.thumbnailKey` (a MinIO key) is a different field from the GraphQL-facing `thumbnailUrl` (a signed URL), computed only on demand. | Lets the storage layer's representation (object keys) change independently of the public API's representation (URLs) — and avoids doing the expensive signing work for fields nobody asked for. | `ProductFieldResolver.java` |

---

## 5. What does what — file map

| File | Responsibility |
|---|---|
| `marketplace/repository/ProductSearchRepositoryImpl.java` | Hand-built native SQL for search/filter/sort; steps 1+2 of two-step paging (ids + count). |
| `marketplace/repository/ProductRepository.java` | `findAllByIdIn` (step 3 of paging), `findByIdAndStatusAndDeletedAtIsNull` (public detail lookup). |
| `marketplace/service/ProductSearchService.java` | Orchestrates the two-step paging recipe, re-orders results, maps to DTOs; the *only* place `LIVE` visibility rules are decided in Java (and even there, only by choosing which repository method to call — the SQL still does the filtering). |
| `marketplace/resolver/ProductResolver.java` | Public `products`/`product` GraphQL queries — no `@PreAuthorize`. |
| `marketplace/resolver/CategoryResolver.java` | Public `categories` query, powers the filter dropdown. |
| `marketplace/resolver/ProductFieldResolver.java` | Lazily resolves `Product.thumbnailUrl` by presigning `ProductDto.thumbnailKey`. |
| `marketplace/mapper/ProductMapper.java` | Entity → DTO mapping; stays a static, dependency-free utility. |
| `content/watermark/WatermarkRenderer.java` / `Java2DWatermarkRenderer.java` | Strategy interface + Java2D implementation for burning a label into a page image. |
| `content/service/PreviewService.java` | The DRM boundary: validates LIVE + page-range, fetches clean bytes, watermarks, returns — never a raw byte passthrough. |
| `content/controller/PreviewController.java` | Public REST `GET /api/products/{id}/preview/{page}`. |
| `frontend/src/hooks/useProductSearch.ts` | URL-params-as-state for the marketplace grid (D6) — shareable, back-button-safe. |
| `frontend/src/components/marketplace/PreviewPane.tsx` | Fetches watermarked PNG, draws to `<canvas>` (not `<img>`), disables right-click/drag/select. |

**Request trace — `GET /marketplace?q=dragon`:**
1. `MarketplacePage` reads `q=dragon` from the URL via `useProductSearch` → debounces 350ms → fires the `products` GraphQL query.
2. `ProductResolver.products()` → `ProductSearchService.searchProducts()`.
3. `ProductSearchRepositoryImpl.searchLiveProductIds()` — native SQL, FTS predicate, `LIMIT`/`OFFSET` → step 1+2 (ids + count).
4. `ProductRepository.findAllByIdIn(ids)` — one `@EntityGraph` query → step 3 (full entities with tags).
5. `ProductSearchService.reorder()` restores the FTS-ranked order.
6. `ProductMapper.toDto()` maps each entity; `thumbnailUrl` stays unresolved unless the client selected it.
7. If selected, `ProductFieldResolver.thumbnailUrl()` presigns it, per-row, only now.
8. Response renders as `ProductCard`s in the grid.

**Request trace — `GET /api/products/{id}/preview/{page}`:**
1. `PreviewController` (public, no auth) → `PreviewService.getPreviewPage()`.
2. Look up the product via `findByIdAndStatusAndDeletedAtIsNull(id, LIVE)` — DRAFT/deleted → 404 immediately.
3. Compute `previewLimit = min(freePreviewPages, documentVersion.pageCount)`; `pageNumber` outside `[1, previewLimit]` → 404.
4. Look up the `ContentPage` row for that page number → fetch clean bytes via `StorageService.get()` (never presigned, see §3.4).
5. `WatermarkRenderer.applyWatermark()` burns the label in.
6. Controller streams the watermarked PNG with `Content-Type: image/png`.

---

## 6. Design decisions and trade-offs

### Decision: hand-written native SQL for search, not Spring Data Specifications or QueryDSL
- **Alternatives considered:** JPA Criteria/Specifications (type-safe, composable predicate builder); QueryDSL (annotation-processor-generated type-safe query DSL).
- **Why we chose this:** The FTS predicate must match the GIN index's expression *verbatim* (§3.1). Criteria can emit the right function call via `cb.function(...)`, but the builder may parenthesize or cast it differently — silently falling back to a sequential scan with **no error, no warning**. For a codebase whose stated purpose is teaching, hiding the actual SQL behind a builder chain also defeats the point of being able to read it. QueryDSL was rejected for a smaller reason: pulling in a whole annotation-processor codegen pipeline for exactly one query surface isn't worth the build-complexity cost.
- **What we gave up:** Type-safety at compile time (a typo in a column name is now a runtime error, not a compile error) and the ability to compose predicates programmatically from smaller pieces.
- **When we would revisit:** If the number of filterable fields keeps growing and the string-building in `ProductSearchRepositoryImpl` becomes genuinely hard to read, Specifications become worth the trade-off *for the non-FTS parts* — the FTS predicate specifically should probably always stay native SQL.

### Decision: two-step ID paging instead of one query with `@EntityGraph` + `Pageable`
- **Alternatives considered:** Single `@EntityGraph` query with `Pageable` (the "obvious" approach); fetching the collection separately per-page-of-results with a `WHERE tag.product_id IN (:ids)` batch query (closer to what Hibernate's `@BatchSize` does automatically).
- **Why we chose this:** The obvious approach triggers `HHH000104` and silently paginates in memory (§3.2) — correct-looking, badly wrong at scale. The batch-fetch alternative is viable but effectively re-invents what step 3 already does via `@EntityGraph`; two clearly-named queries (ids, then full entities) is easier to reason about and test than a batch-size tuning knob.
- **What we gave up:** A third round-trip to the database on every listing request (3 statements instead of 1) — a deliberate, measured trade of query *count* for query *correctness and cost*. See the measured proof below.
- **When we would revisit:** If p99 latency on the listing endpoint becomes dominated by round-trip count rather than data volume, a keyset/cursor-based approach (see the offset-vs-keyset note below) would reduce this further.

### Decision: offset-based pagination (`page`/`size`), not cursor/keyset pagination
- **Alternatives considered:** Keyset pagination (`WHERE created_at < :lastSeenCursor`), Relay-style cursor connections.
- **Why we chose this:** The GraphQL schema (already in place before this phase) is `ProductPage { content, totalElements, totalPages, pageNumber }` — an offset shape. Numbered pagination ("page 3 of 12") is also a better UX fit for a browsing marketplace than infinite scroll, and offset pagination is trivial to make shareable via a URL `?page=N`.
- **What we gave up:** Offset pagination gets more expensive as `OFFSET` grows (Postgres still has to skip N rows), and pages can shift if new products are added between two page loads by the same user (an item can appear twice or be skipped). Keyset pagination doesn't have either problem.
- **When we would revisit:** If the marketplace catalog grows into the hundreds of thousands of `LIVE` products and deep pagination (`page=500`) becomes a real, observed cost — that's the point where a keyset rewrite pays for itself.

### Decision: watermarking streamed through the backend, not presigned URLs, for preview tiles
- **Alternatives considered:** Presign the tiles bucket like the thumbnails bucket, with a short TTL.
- **Why we chose this:** Presigning is fundamentally "hand out a temporary URL to the exact bytes stored." There is no TTL short enough to make that safe for content the whole product exists to protect (§3.4) — the download happens the instant the URL is issued, TTL or not.
- **What we gave up:** Every preview-page view now costs a real CPU-bound watermark render on the backend, and the endpoint is public, so it's a DoS surface. Phase 3 bounds this with the strict page-range check (only the first `freePreviewPages` pages are ever reachable) plus an optional read-through cache under a `preview/` key prefix; real rate limiting is deferred to MVP-2.
- **When we would revisit:** If preview traffic volume makes the per-request render cost material, the read-through cache (deterministic output per page) removes the recurring cost entirely — first render pays for all future ones.

---

## 7. Interview questions

### Beginner
**Q: What's the difference between `WHERE title LIKE '%dragon%'` and full-text search?**
A: `LIKE` does a literal substring scan and generally can't use a normal B-tree index for a leading wildcard, so it's slow at scale and doesn't understand word forms — `"dragon"` won't match `"dragons"`. Full-text search normalizes both the stored text and the query into `tsvector`/`tsquery` (lowercase, stemmed, stopwords removed), and a GIN index makes the lookup fast even on large tables.

**Q: Why does `Product.creator` return `CreatorSummary` instead of the full `User` type?**
A: The marketplace is public — anonymous visitors can query it. `User` carries `email`, which is PII we never want exposed to an unauthenticated caller. `CreatorSummary` only has `id` and `displayName`, so there's no field to accidentally select that would leak it.

### Intermediate
**Q: Walk me through what N+1 means and how you avoided it here.**
A: N+1 is fetching N parent rows with one query, then making one more query per row for a related collection — N+1 queries total instead of a constant number. Naively, `@EntityGraph` fixes that by joining the collection into a single query. But combine that join with `Pageable` (`LIMIT`/`OFFSET`) and Hibernate can't paginate correctly anymore, because the join can multiply rows — so it logs `HHH000104` and silently loads everything into memory to paginate there instead, which is *worse* than N+1 at any real scale. I fixed it with two-step ID paging: page the ids with a plain query (no join), then fetch the full entities for exactly those ids with one `@EntityGraph` query and no `LIMIT`. Three statements total, constant regardless of page size, verified with `Statistics.getPrepareStatementCount()` in a test.

**Q: Why is the free-preview endpoint a 404 for a page number beyond `freePreviewPages`, instead of a 403?**
A: A 403 tells the caller "this exists, but you're not allowed to see it" — which, for a *page number*, doesn't leak much on its own, but the same principle applies to the product-visibility check right next to it: returning 403 for a DRAFT product would let anyone enumerate which product ids exist even if they can't view them. Using 404 everywhere for "hidden," consistently, means a caller can never distinguish "doesn't exist" from "exists but hidden."

### Advanced / follow-up probes
**Q: Your FTS predicate is a hand-built string, not a parameterized Criteria query. How do you know it's not a SQL injection risk?**
A: Every value that comes from user input — the search text, the category slug, the min/max price — goes in as a *named parameter* (`:q`, `:categorySlug`, ...) via `query.setParameter()`, never string-concatenated into the SQL. The only thing that's string-built from user input is `sortBy`, and that's never concatenated either — it's matched against a fixed `switch` of five known literal strings first, and anything that doesn't match falls through to a safe default. So the SQL text itself is always one of a small, fixed set of strings; only the *bind values* vary, exactly like a normal `PreparedStatement`.

**Q: The two-step paging fix adds a third database round trip. Isn't that a regression?**
A: In statement count, yes — 3 instead of 1. But the 1-statement version wasn't actually doing 1 statement's worth of work: it was silently loading the *entire* `LIVE` product table (with joined tags) into the JVM heap and paginating there, on every single page request, for the life of the query. Three small, index-backed statements are cheaper in both latency and memory than one query that ignores its own `LIMIT`. It's not "1 fast query vs. 3 queries," it's "1 query that lies about being fast vs. 3 queries that are honestly cheap."

### "Tell me about a bug you fixed"
**Q: Tell me about a subtle bug from this phase.**
A: While wiring up the two-step paging fix, the first version fetched the id page, then called `findAllByIdIn(ids)`, and returned that result directly — and page 1 of a relevance-ranked search came back in the wrong order. The `@EntityGraph` join on step 3 doesn't preserve the `ORDER BY` from step 1; its row order follows whatever join plan Postgres picks, not the ranking. The fix was to re-order the step-3 result in Java, using a `Map<Long, Product>` keyed by id and walking it in the order from step 1's id list. The lesson: once you split "which rows" from "which order," you have to re-apply the order yourself — nothing does it for you automatically.

---

## 8. Gotchas and bugs we hit

| Symptom | Root cause | Fix | Lesson |
|---|---|---|---|
| Relevance-ranked search results came back in the wrong order | Step 3's `@EntityGraph` `IN` query doesn't preserve step 1's `ORDER BY` — its row order follows the join plan. | Re-order in Java via a `Map<Long, Product>` walked in the id-list order. | Splitting "which rows" (step 1) from "full entity fetch" (step 3) means you own re-applying the order yourself. |
| A first draft of `ProductSearchRepositoryImpl` injecting `ProductRepository` directly (to call `findAllByIdIn` from inside the same class) failed to wire up. | Circular bean dependency: `ProductRepository`'s Spring Data proxy is composed *from* `ProductSearchRepositoryImpl`, so `ProductSearchRepositoryImpl` can't also depend on the finished `ProductRepository` proxy at construction time. | Moved step 3 (the `findAllByIdIn` call + re-order) up into `ProductSearchService`, which depends on `ProductRepository` normally — `ProductSearchRepositoryImpl` now only returns raw ids + a count, nothing more. | A repository *fragment* implementation (the "Impl" suffix pattern) should never depend on the composed repository interface it's a fragment of. |
| The REST preview endpoint returned 500 instead of 404 for an out-of-range page during first manual testing. | No `@RestControllerAdvice` existed for REST controllers — only `GlobalGraphQlExceptionHandler`, which only intercepts exceptions from GraphQL data fetchers. An unhandled `ResourceNotFoundException` thrown from a plain `@RestController` falls through to Spring Boot's generic error handling, which defaults to 500 for an unmapped `RuntimeException`. | Added `GlobalRestExceptionHandler` (`@RestControllerAdvice`) mapping `ResourceNotFoundException` → 404 and `BusinessException` → its `ErrorCode`'s HTTP status. | GraphQL and REST exception handling are two *separate* pipelines in the same Spring app — having one doesn't give you the other. |

---

## 9. New vocabulary

| Term | One-line meaning |
|---|---|
| GIN index | A PostgreSQL index type ("Generalized Inverted Index") good at indexing composite/array-like values — what full-text search and `tsvector` columns are indexed with. |
| `tsvector` / `tsquery` | PostgreSQL's normalized, searchable representations of a document and a search query, respectively. |
| `ts_rank` | A PostgreSQL function that scores how well a `tsvector` matches a `tsquery`, used to order search results by relevance. |
| N+1 query problem | Fetching N parent rows with one query, then one additional query per row for a related collection — N+1 queries where 1 (or a small constant) would do. |
| `HHH000104` | Hibernate's warning ID for "pagination + collection-fetch join in the same query" — it silently falls back to in-memory pagination. |
| Entity graph (`@EntityGraph`) | Spring Data JPA's declarative way to say "eagerly join these lazy associations in this one query" without writing a custom `JOIN FETCH`. |
| Offset vs. keyset pagination | Offset (`LIMIT`/`OFFSET`, "page N") is simple but gets slower and less stable as the offset grows; keyset (`WHERE cursor_col < :lastSeen`) stays fast and stable but can't jump to an arbitrary page number. |
| Presigned URL | A time-limited, signed URL that grants temporary access to one object in a storage bucket without requiring the caller to authenticate to the storage service itself. |
| Strategy pattern | A design pattern where an interface defines one operation with several interchangeable implementations, chosen at runtime — the caller depends only on the interface. |
| Field resolver (GraphQL) | A method bound to exactly one field of a GraphQL type, run only if a client's query selects that field — lets expensive fields stay uncomputed when unused. |

---

## 10. If I had to defend this in a code review

The strongest points: visibility rules live in SQL, not scattered `if` checks, so there's exactly one place to audit for "can a hidden product leak"; the N+1 fix is measured, not assumed (a test asserts the exact statement count); and the DRM boundary (never presign the tiles bucket) is enforced structurally — `PreviewService` has no method that returns unmodified storage bytes at all, so there's no accidental code path to leak through.

The weakest point I'd fix first: the preview endpoint's only DoS protection right now is the page-range check — it's public, unauthenticated, and does real CPU work (image decode + watermark render) on every uncached request. The read-through cache under a `preview/` prefix (documented as "optional, skip if it complicates the step" and not implemented in this pass) would remove almost all of that cost after the first view of each page, and I'd prioritize adding it before real user traffic, ahead of the MVP-2 rate limiting that's currently the only other planned mitigation.
