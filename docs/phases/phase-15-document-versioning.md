# Phase 15 — Full document versioning

> Spec for an unattended run. Decisions are numbered (D1…) and referenced from code comments.
> Requirements: **MVP2-05**.
> Depends on: Phase 14 (or 13 if 14 is deferred). Target branch: `feature/secure-leaf-mvp2`.

## Context

The schema has supported versions since V1:
- `document_versions` has `(product_id, version_number)` unique.
- Each entitlement points at a specific `document_version_id`.

But MVP1 assumes one version per product. `PreviewService` uses `findFirstByProductIdOrderByVersionNumberDesc`. A re-upload has no defined behaviour. Creators need to fix typos or publish a 2nd edition without taking away what buyers paid for.

## Decisions

- **D1 — The current version is explicit.** Migration: `products.current_document_version_id BIGINT REFERENCES document_versions(id)`, backfilled to each product's latest processed version. Stop inferring "current" by ordering.
  - The marketplace, previews and **new purchases** use the current version.
  - Buyers read the version their entitlement points at.
- **D2 — Uploading a new version.**
  - REST `POST /api/products/{id}/versions` (multipart, owner-only). Same validation as the first upload: PDF magic bytes, 50 MB.
  - Creates `version_number = max + 1` and enqueues processing.
  - The product stays LIVE on its **old** current version while the new one processes.
  - On success, the pointer moves (in the same transaction as job completion) and the creator is notified.
  - On failure, the pointer doesn't move, and the creator sees the failed version with a retry option (reuse the Phase 06 `retryProcessing`, generalised to take a version id).
- **D3 — Existing buyers: the creator chooses per version.** The upload form has `updatePolicy: NEW_BUYERS_ONLY | FREE_UPDATE_FOR_EXISTING`.
  - With `FREE_UPDATE_FOR_EXISTING`, once processing succeeds, a batch job moves every ACTIVE entitlement for the product to the new version. It runs in chunks of 500 with a progress log, is idempotent, and is resumable.
  - Otherwise existing entitlements stay where they are.
  - Store the policy on `document_versions.update_policy`.
- **D4 — Buyer UI.** The library shows "v{n}" and, when a newer version exists that they *don't* have, "A new edition is available" linking to the product page. The link is informational only. Buying an upgrade is **out of scope**, and the existing one-active-entitlement rule (V4) stays unchanged.
- **D5 — Old versions are kept.** Never delete the tiles of any version that an entitlement still points at. A creator can't delete a version that has entitlements. Soft retirement only (`retired_at`).
- **D6 — Viewer and cache.** They already key on the entitlement's version (05A, and D2 of Phase 13). Add tests proving that a buyer on v1 keeps reading v1 after v2 becomes current.
- **D7 — Creator UI.** On the dashboard's product page, a version history table (number, date, pages, status, policy, number of buyers on it) and an "Upload new version" flow showing processing status.

## Acceptance criteria
1. Backfill migration: each existing product points at its latest version, and the tests from before the migration still pass.
2. Uploading v2 keeps the product LIVE on v1 until processing finishes. The pointer then moves, and the preview serves v2.
3. `NEW_BUYERS_ONLY`: an existing buyer still gets v1 tiles, and a new buyer gets v2.
4. `FREE_UPDATE_FOR_EXISTING`: every ACTIVE entitlement moves to v2. Re-running the job changes nothing. Interrupting midway and resuming completes it (simulate a crash between chunks).
5. A failed v2 leaves the pointer on v1, and retry works.
6. Deleting a version that has entitlements is rejected. Tiles of referenced versions are never removed.
7. Non-owners can't upload versions (object-level authorisation test).
8. Frontend: the version table renders, the upload flow shows states, and the library shows the new-edition hint.

## Out of scope
- Paid upgrades.
- Diffs between versions.
- Per-page changelogs.

## Learning note
Create `docs/learning-notes/phase-15-document-versioning.md`. Headline topics:
- immutable versions + a pointer (the same idea as git refs and Docker tags)
- why buyers' rights pin to a version
- backfill migrations
- idempotent, resumable batch jobs (chunking, checkpoints)
- zero-downtime content updates
