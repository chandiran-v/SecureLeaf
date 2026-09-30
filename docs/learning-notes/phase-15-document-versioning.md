# Phase 15 — Full document versioning

> **Status:** Done (Phase 14 was parked, so this builds directly on Phase 13)
> **Built:** 2026-09-30
> **Requirement IDs covered:** MVP2-05 (spec: `docs/phases/phase-15-document-versioning.md`)
> **Commits:** see `git log --grep "Phase 15"`

---

## 1. What we built, in plain English

A creator can now publish a **second edition** of a document without touching the first. They open the product's *Versions* page, upload a new PDF and choose what happens to people who already bought it:

- **New buyers only** — existing buyers keep reading the edition they paid for; everyone who buys from now on gets the new one.
- **Free update for existing buyers** — once the new edition has finished processing, a background job quietly moves every current buyer onto it.

While the new PDF is being converted, the product stays on sale exactly as before. Only when processing **succeeds** does the shop's "current edition" switch over. If processing fails, nothing changes for anybody and the creator sees the failed version in a table with a **Retry** button. A buyer's library shows which version they own ("v1") and, when a newer edition exists that they do not have, a link saying "A new edition is available" (it is only information — buying an upgrade is out of scope).

**Before this phase:** the database could hold several versions, but the code assumed one. A re-upload overwrote version 1 in place, and "the current version" was guessed by sorting.
**After this phase:** versions are immutable once processed, "current" is an explicit pointer, buyers are pinned to the version they bought, and moving buyers to a new edition is a deliberate, resumable batch job.

---

## 2. Why it matters

Creators make typos and publish 2nd editions. Without versioning the only choices are "re-upload over the top" (which can silently swap what a buyer paid for, or break a half-read document) or "never fix anything". And because the viewer, the tile cache and refunds all hang off *which version an entitlement points at*, getting this wrong would be a correctness and trust problem, not a cosmetic one.

It is built now because Phase 13's cache key already includes `documentVersionId`, and Phase 5's viewer already reads the **entitlement's** version, not "the latest". The hard plumbing existed; this phase adds the rules around it.

---

## 3. New concepts introduced

### 3.1 Immutable versions + a movable pointer

**What it is:** a processed version never changes. "Which version is live" is a separate, single pointer (`products.current_document_version_id`) that moves from one immutable version to the next.
**The analogy:** git. Commits never change; a branch (`main`) is a pointer that moves to a newer commit. Docker is the same: an image digest is immutable, the tag `latest` moves.
**Why we needed it here:** MVP1 *inferred* "current" as "highest version number" (`findFirstByProductIdOrderByVersionNumberDesc`). That is wrong the moment v2 exists but is still converting — the public preview would start serving a half-built document. An explicit pointer means "uploaded" and "live" are different facts.
**How it works:**
1. A new version is a new row (`version_number = max + 1`), never an edit.
2. The pointer moves in the **same transaction** that marks the job COMPLETED (`DocumentProcessingService.java:236-270`), under a row lock on the product so two versions finishing together can't fight (`findByIdForUpdate`, line 245).
3. Marketplace, preview and new purchases read the pointer (`PreviewService`, `FulfillmentService`, `ProductSearchService`); the viewer keeps reading the entitlement's own version.

**In our code:**
```java
// DocumentProcessingService.java:246-254
DocumentVersion previous = product.getCurrentDocumentVersion();
boolean firstVersion = previous == null;
boolean becomesCurrent = docVersion.getRetiredAt() == null
        && (firstVersion || docVersion.getVersionNumber() > previous.getVersionNumber());
if (becomesCurrent) {
    product.setCurrentDocumentVersion(docVersion);
    product.setCoverImageUrl(docVersion.getThumbnailMinioKey());
}
```
**What breaks without it:** preview/purchase would serve a version that is still converting (or failed), and there is no way to say "keep v1 live while v2 is a draft".

### 3.2 Why a buyer's rights pin to a version

**What it is:** an entitlement row stores `document_version_id`. What the buyer may read is *that* version, forever, unless something deliberately moves them.
**The analogy:** buying a printed book. A publisher releasing a 2nd edition does not reach into your bookshelf and swap your copy.
**Why we needed it here:** the creator's choice ("new buyers only" vs "free update") is only expressible if buyers are *not* automatically on "latest". It also keeps refunds, access logs and the tile cache (`TileCacheKey` includes `documentVersionId`) unambiguous.
**How it works:** `SecureTileService.java:136` reads `entitlement.getDocumentVersion()`, never `product.getCurrentDocumentVersion()`. `ViewerIT#buyerOnV1_keepsReadingV1_afterV2BecomesCurrent` proves it: after the pointer moves to a 7-page v2, the session still reports 5 pages, the access log records v1, and page 6 (v2 only) is a 404.
**What breaks without it:** buyers lose or gain pages they never paid for; a refund/forensics question ("which file did they see?") has no answer.

### 3.3 Backfill migrations

**What it is:** a schema migration that adds a column *and* fills it for rows that already exist.
**The analogy:** adding a "room number" column to a hotel register — you must also write the room number next to every guest already checked in, or the new column is useless for them.
**Why we needed it here:** existing products need a pointer the moment V12 lands. `V12__document_versioning.sql` sets each product's pointer to its latest **processed** version (`UPDATE … FROM (SELECT DISTINCT ON (product_id) …)`), leaves it NULL when nothing finished processing, marks old versions `NEW_BUYERS_ONLY` and "migration already done".
**How it works / how it is tested:** `VersionBackfillMigrationIT` builds a database at schema **V11**, inserts MVP1-shaped data, *then* lets Flyway apply V12 and asserts the result. A test that starts from an empty, fully-migrated schema proves nothing about a backfill — there is nothing to back-fill.
**What breaks without it:** every pre-existing product would have a NULL pointer, so previews, purchases and the marketplace page count would all break in production on deploy day even though all tests (which build fresh databases) pass.

### 3.4 Idempotent, resumable batch jobs

**What it is:** a job over many rows that can be run twice, or interrupted and restarted, and still ends in the right state.
**The analogy:** painting a fence and marking each painted panel. If you stop for lunch you don't need a diary — you just look for unpainted panels. Painting an already-painted panel changes nothing.
**Why we needed it here:** `FREE_UPDATE_FOR_EXISTING` must move potentially thousands of entitlements. One huge transaction would hold locks for a long time and lose everything on a crash.
**How it works:**
1. **Chunking** — `EntitlementMigrationService.migrate` moves 500 rows per transaction (`TransactionTemplate`, committed per chunk) and logs progress.
2. **The checkpoint is the data.** The chunk query selects ACTIVE entitlements *not yet on the target version* (`EntitlementRepository.java:90`). A first run, a re-run (empty result) and a resume after a crash are all the same query.
3. **`entitlements_migrated_at`** on the version is stamped only when a chunk finds nothing left. A poller (`EntitlementMigrationJob`, every 5 s) looks for processed `FREE_UPDATE_FOR_EXISTING` versions where it is still NULL — so a restart resumes automatically.
4. The UPDATE re-checks `status = 'ACTIVE'` so a refund landing between the SELECT and the UPDATE is not overwritten.
5. If a newer version became current before this one's migration ran, the job stamps it "skipped" instead of moving buyers *backwards*.

**In our code:**
```java
// EntitlementMigrationService.java:68-73
Integer moved = tx.execute(status -> {
    List<Long> ids = entitlementRepository.findChunkToMigrate(plan.productId(), versionId, chunkSize);
    if (ids.isEmpty()) return 0;
    return entitlementRepository.moveToVersion(ids, versionId);
});
```
**What breaks without it:** a crash at 40 % leaves buyers split across versions forever, or a retry double-processes; a single-transaction UPDATE on a big product blocks refunds and purchases for the whole duration.
**The test:** `freeUpdate_movesEveryActiveEntitlement_idempotently_andResumesAfterACrashBetweenChunks` uses chunk size 2, stops after one chunk (`migrate(v2, 1)` — the simulated crash), asserts 2 moved/2 not and *not* stamped, resumes via the poller, then runs again and asserts nothing changes. A REVOKED buyer stays on v1.

### 3.5 Zero-downtime content updates

**What it is:** publishing new content without taking the old content offline.
**How we get it:** (a) the new version is built *beside* the live one under a different key (`products/{id}/v{n}/tiles/…` is already versioned); (b) the switch is one pointer write inside one transaction; (c) the product's status is untouched by a later version's success *or* failure (`firstVersion` guards both the LIVE flip and the FAILED flip). Readers never see a half-built state.
**What breaks without it:** re-uploading over v1 (the MVP1 behaviour) deleted v1's ContentPage rows mid-conversion — a buyer reading page 7 at that moment got a 404.

### 3.6 Soft retirement

A version is never deleted; it is *retired* (`retired_at`). Retirement is refused if the version is current, still processing, or **any** entitlement (even REVOKED) references it (`DocumentVersionService.retireVersion`). Only then are its tile *objects* removed from storage (rows stay: `viewer_access_logs` has a foreign key to `content_pages`). "Never delete the tiles of a version an entitlement points at" is therefore enforced in one place, with a test that checks the tile still exists after the rejected attempt.

---

## 4. Best practices applied

| Practice | What we did | Why it matters | Where |
|---|---|---|---|
| Explicit state over inference | `current_document_version_id` instead of ORDER BY | "uploaded" ≠ "live" | `V12__document_versioning.sql:8`, `Product.java:83` |
| Backfill + test the backfill | V12 fills the pointer, tested from schema V11 | Fresh-DB tests can't catch a bad backfill | `VersionBackfillMigrationIT` |
| Object-level authorisation (BOLA) | Owner check on upload, list, retire, retry; 403/ACCESS_DENIED | OWASP API #1 | `DocumentVersionService.java` `assertOwnership`; `DocumentVersioningIT#uploadVersion_rest_isOwnerOnly` |
| Row lock for numbering and "one in flight" | `findByIdForUpdate` before `max+1` | Two uploads can't get the same number | `DocumentVersionService.createVersion` |
| Chunked, committed-per-chunk batch | `TransactionTemplate` in a loop | Short locks, crash-safe | `EntitlementMigrationService.java` |
| Checkpoint in the data | "rows not yet on target" | No cursor to lose | `EntitlementRepository.java:90` |
| Re-check state inside the write | `AND status = 'ACTIVE'` in the UPDATE | TOCTOU with refunds | `EntitlementRepository.java:99` |
| Same validation as first upload | magic bytes + 50 MB via one helper | One rule, one place | `DocumentUploadController.validatedPdfBytes` |
| No orphan objects | raw PDF deleted if the DB write is rejected | Storage doesn't leak | `DocumentUploadController.uploadNewVersion` |
| No new Postgres enum value | reuse `PROCESSING_COMPLETE/FAILED` notifications with new text | Avoids an `ALTER TYPE` migration for a wording change | `NotificationService.notifyVersionProcessed` |

---

## 5. What does what — file map

| File | Responsibility |
|---|---|
| `db/migration/V12__document_versioning.sql` | pointer column + backfill, `update_policy`, `entitlements_migrated_at`, `retired_at` |
| `content/controller/DocumentUploadController.java` | `POST /api/products/{id}/versions` (multipart, owner-only); `/document` is now first-upload-only |
| `content/service/DocumentVersionService.java` | create version + job under a product lock; version history; retire |
| `content/service/DocumentProcessingService.java` | pipeline; moves the pointer atomically in `markLive`; failure of a later version doesn't fail the product |
| `creator/service/ProductRecoveryService.java` | `retryProcessing(productId, versionId?)` generalised |
| `commerce/service/EntitlementMigrationService.java` | the chunked, idempotent entitlement mover |
| `commerce/service/EntitlementMigrationJob.java` | the 5-second poller that makes it resumable |
| `content/service/PreviewService.java`, `commerce/service/FulfillmentService.java`, `marketplace/service/ProductSearchService.java` | read the pointer |
| `commerce/service/CommerceMapper.java` | `versionNumber`, `newEditionAvailable` for the library |
| `content/resolver/DocumentVersionResolver.java` | `productVersions`, `retireDocumentVersion` |
| `frontend/src/pages/creator/ProductVersionsPage.tsx` | history table + upload flow with states |
| `frontend/src/pages/buyer/LibraryPage.tsx` | "v{n}" and the new-edition hint |

**Request trace — uploading v2 with "free update":**
1. `POST /api/products/5/versions` (JWT, CREATOR role) → `DocumentUploadController.uploadNewVersion` validates PDF/size/policy, stores the raw PDF →
2. `DocumentVersionService.createVersion` locks the product row, rejects if a version is already processing, inserts version 2 + a QUEUED job → **202**. The product is still LIVE on v1 →
3. `ProcessingJobWorker` claims the job (unchanged Phase 2 machinery) → `DocumentProcessingService` renders tiles under `products/5/v2/…` →
4. `markLive` (one transaction): version processed, pointer → v2, cover image → v2 thumbnail, job COMPLETED, creator notified →
5. `EntitlementMigrationJob` (next tick) sees v2 is `FREE_UPDATE_FOR_EXISTING` and un-stamped → moves entitlements 500 at a time → stamps `entitlements_migrated_at` →
6. The buyer's next viewer session reads v2 (their entitlement now points at it); the library shows "v2".

---

## 6. Design decisions and trade-offs

### Decision: explicit pointer column (D1)
- **Alternatives considered:** keep `ORDER BY version_number DESC LIMIT 1`; a `status = CURRENT` flag on each version row.
- **Why:** ordering can't express "v2 exists but isn't live". A flag on versions needs two rows updated atomically (unset old, set new) and a partial unique index to guarantee one current; a single pointer on the product is one write and "at most one" is structural.
- **What we gave up:** a circular foreign key (`products` ↔ `document_versions`), handled with `ON DELETE SET NULL`; and every test fixture that inserts versions by hand must now set the pointer.
- **When we'd revisit:** never for correctness; if we ever want scheduled "go live at 9am" switches we'd add a `publish_at`.

### Decision: migrate entitlements with a poller + data checkpoint (D3)
- **Alternatives:** run the batch inline in the job-completion transaction; a message queue (Kafka/SQS) with one message per chunk; a cursor table storing the last id.
- **Why:** inline = giant transaction and a failure would roll back the pointer move too. A queue is new infrastructure for ~10 uploads/day (same argument as Phase 2's Postgres job queue). A cursor adds state that can drift; "rows not yet on target" cannot.
- **What we gave up:** up to ~5 seconds between "v2 is live" and "buyers start moving", and buyers are briefly split across versions during a long migration.
- **Revisit when:** many products migrate at once or migrations take minutes — then chunk results into a proper queue with a worker pool.

### Decision: one version in flight per product
- **Alternatives:** allow parallel processing of v2 and v3.
- **Why:** with parallel jobs the "higher number wins" rule is still correct, but the creator UI, retry semantics and the migration-skipped edge case all get harder to explain, and the render pool (Phase 12) is small.
- **Gave up:** a creator can't queue v3 until v2 finishes or fails.

### Decision: reuse existing notification types
- **Why:** `notification_type` is a Postgres enum; adding a value needs its own migration (V11's lesson). The only difference is wording.
- **Gave up:** the UI can't filter "version" notifications separately.

---

## 7. Interview questions

### Beginner
**Q: Why not just overwrite the PDF when a creator uploads a fix?**
A: Because buyers paid for a specific file, and a half-finished overwrite can break someone mid-read. We keep the old version, build the new one beside it, and switch a pointer when it's ready.

**Q: What is a "current version pointer"?**
A: One column on the product that says which version is live. It's the same idea as a git branch pointing at a commit, or a Docker `latest` tag pointing at an image. Versions are immutable; the pointer moves.

### Intermediate
**Q: Why does the entitlement store a version id instead of reading "latest"?**
A: So the creator can choose. "New buyers only" needs existing buyers to *stay* on what they bought, which is only possible if they're pinned. It also makes refunds, access logs and the cache key unambiguous.

**Q: What does a backfill migration do, and how do you test it?**
A: It fills a new column for rows that already exist. You test it by building the database at the *previous* schema version with realistic data, then applying the migration and asserting on the result — not by starting from a fresh database.

**Q: A v2 upload fails. What happens to the product?**
A: Nothing. The product stays LIVE on v1. The failed version shows in the creator's history with its reason and a Retry. Only a product with *no* current version goes FAILED.

### Advanced / follow-up probes
**Q: How do you make a batch job over 100 000 rows resumable?**
A: Pick a checkpoint that can't drift. Ours is "ACTIVE entitlements not yet on the target version" — progress is literally the data. Chunks commit separately, completion is stamped only when a chunk finds nothing, and a poller re-runs anything un-stamped. Running it again is a no-op, which is what makes crash recovery safe.

**Q: Two versions finish processing at the same moment. Who wins?**
A: `markLive` takes a row lock on the product (`SELECT … FOR UPDATE`), then only moves the pointer if the new version number is higher. So the later-numbered one wins regardless of finish order, and there's no lost update.

**Q: What if a refund lands while the migration is running?**
A: The UPDATE repeats `status = 'ACTIVE'`, so a row that was revoked between the SELECT and the UPDATE is left alone. Even if it were moved, the viewer's entitlement check still rejects non-ACTIVE rows — the migration can't grant access.

**Q: Why can't you retire a version a REVOKED entitlement points at?**
A: The foreign key still references it, and it's audit history. Retirement removes the tile files; access logs reference page rows, so those rows stay.

### "Tell me about a bug you fixed"
**Q: Tell me about a subtle bug from this phase.**
A: While reading the pipeline I noticed `markLive` set the product to LIVE unconditionally, and the failure path set it to FAILED. That was harmless with one version per product, but with versioning a successful v2 would have *republished* a product the creator had deliberately unpublished, and a failed v2 would have taken a healthy product offline. I guarded both with "is this the product's first version?" and wrote tests for the failure case (product stays LIVE, pointer unmoved). Lesson: when you generalise "one thing" to "many things", grep every place that assumed one.

---

## 8. Gotchas and bugs we hit

| Symptom | Root cause | Fix | Lesson |
|---|---|---|---|
| Dozens of existing tests failed after adding the pointer | Fixtures insert `DocumentVersion` rows by hand and never set the pointer, so fulfilment/preview found "no current version" | Set the pointer in each fixture (and the shared `AbstractCommerceIT`) | An explicit pointer is stricter than an inference; it surfaces every place that cheated |
| Product would be republished / taken down by a later version | `markLive` and `handleFailure` assumed one version per product | `firstVersion` guard; failure of a later version only notifies | Audit every state write when a cardinality changes |
| Cover image changed while v2 was still converting | Thumbnail stage wrote `product.coverImageUrl` | Thumbnail recorded on the version only; cover set when the pointer moves | "Live" data must change in the same step as the pointer |
| Crash-simulation test raced the real poller | The migration poller ran in the test context and finished the job between chunks | Poller is switched off in the test profile; tests drive `EntitlementMigrationJob.runOnce()` and the service directly | Background schedulers and deterministic tests don't mix — give the timer an off-switch |
| "v2 is processing" assertions were flaky | The suite's real 5 s job poller could claim a QUEUED job mid-assertion | `stageVersion` creates the version and flips its job to PROCESSING in one transaction | Make the intermediate state *constructible*, don't race it |
| Retry tests timed out only in the full `verify` run | `TileCacheIT`/`RenderBackpressureIT` create their own cached Spring contexts whose job pollers stayed ON; they claimed the re-queued job and failed it because their in-memory storage is a different instance | `processing.worker.enabled=false` on those secondary contexts (same rule ProductStatsIT already followed) | A cached test context keeps its schedulers running for the whole JVM — every secondary context must switch off background workers |
| Backfill test crashed inserting a category | Seed categories (V3) have explicit ids, so the sequence was never advanced | Use `SELECT min(id) FROM categories` | Seed data with explicit ids + sequences = duplicate key surprises |
| `startViewerSession` in a test killed the real session | Each start supersedes the previous one (Phase 5) | Read the page count first, start the real session last | Know the side effects of "read-looking" mutations |

---

## 9. New vocabulary

| Term | One-line meaning |
|---|---|
| Immutable version | A processed version that is never edited; changes create a new one |
| Current-version pointer | The single column saying which version is live (like a git ref / Docker tag) |
| Backfill | Filling a newly added column for rows that already exist |
| Update policy | Creator's per-version choice for existing buyers: `NEW_BUYERS_ONLY` / `FREE_UPDATE_FOR_EXISTING` |
| Chunking | Processing a large set in small committed batches |
| Checkpoint | State that tells a restarted job where it stopped (ours: the data itself) |
| Idempotent | Doing it twice has the same effect as once |
| Soft retirement | Marking something retired (`retired_at`) instead of deleting it |
| Zero-downtime update | Publishing new content while the old stays available |
| BOLA | Broken Object Level Authorization — acting on someone else's object id |

---

## 10. If I had to defend this in a code review

- **Strongest points:** versions are immutable and the switch is a single pointer write in the same transaction as job completion; buyers are pinned, with a test proving a v1 buyer keeps v1 after v2 is current; the batch job's correctness comes from the data, not from a cursor, and the crash/resume/idempotency cases are tested; the backfill is tested from the previous schema.
- **Weakest point:** the migration poller is a simple `@Scheduled` loop — on several app instances every instance would run it. It is safe (idempotent UPDATEs) but wasteful; I'd add `SELECT … FOR UPDATE SKIP LOCKED` on the version row (the Phase 2 queue trick) or a lock so one instance owns a migration. Second weakest: during a long migration an active reading session keeps its old page count until the next session starts.
- **Explicitly not done (out of scope):** paid upgrades, diffs between versions, per-page changelogs.
