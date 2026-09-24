# Phase 08 — Admin panel

> Spec for an unattended run. Decisions are numbered (D1…) and referenced from code comments.
> Requirements: **ADMIN-01..04, AUTH-08**.
> Depends on: Phase 07.

## Context

- The `ADMIN` role exists in the enum.
- The schema declares `allUsers`, `allProducts`, `suspendUser`, `approveProduct` and `takeDownProduct`, but none of them are implemented.
- Login already rejects `SUSPENDED` accounts (`AuthService.java:273`). But an **access token issued before the suspension keeps working until it expires** (15 min), and refresh tokens are not revoked.
- There is no way to become an admin.

## Decisions

- **D1 — Bootstrapping admins.** Env var `ADMIN_EMAILS` (comma-separated). An `ApplicationRunner` grants `ADMIN` to those existing users at startup, and registration grants it to matching new users. It is idempotent. There is no GraphQL mutation that grants ADMIN (that avoids privilege-escalation surface). Document the approach in the learning note.
- **D2 — Authorisation.** Every admin operation has `@PreAuthorize("hasRole('ADMIN')")` **and** an integration test proving that a BUYER and a CREATOR get `ACCESS_DENIED`. Put the admin resolvers in their own package, `com.secureleaf.admin`.
- **D3 — Users (ADMIN-01).** Replace `allUsers: [User!]!` with
  `adminUsers(filter: AdminUserFilter, page: Int = 0, size: Int = 25): AdminUserPage!`.
  - Filter: `search` (email or display name, case-insensitive), `role`, `status`.
  - `AdminUser` adds `accountStatus`, `authProvider`, `roles`, `createdAt`, `productCount`, `purchaseCount`. The counts come from batched aggregates, not N+1.
  - Size is capped at 100.
- **D4 — Suspend and reactivate (ADMIN-02, AUTH-08).**
  - `suspendUser(userId, reason: String!)`:
    - set `SUSPENDED`
    - **revoke all refresh tokens**
    - end their viewer sessions (DB rows `end_reason = 'REVOKED'`, and delete the Redis active keys)
    - add the user id to the Redis set `auth:suspended`
  - `reactivateUser(userId)` reverses the status and the set membership.
  - `JwtAuthenticationFilter` rejects any token whose subject is in `auth:suspended`. That is one O(1) `SISMEMBER` per request. The learning note compares this with the alternatives: short TTL alone (a 15-min window), loading the user per request (a DB hit on every call), and a token deny-list by `jti`.
  - An admin can't suspend themselves or another admin (`INVALID_INPUT`).
  - If Redis is unavailable, this check **fails open**: the request continues and an ERROR is logged. The DB status is still enforced at login and refresh, so the worst case is the ≤15-minute access-token window. The alternative, failing closed, would log out every user during a Redis blip. State this trade-off plainly in the note. It is a good interview discussion, because it looks like it breaks the project's "fail closed" rule, and the note must explain why it's justified here.
- **D5 — Products (ADMIN-03).** Replace `allProducts` with `adminProducts(filter: AdminProductFilter, page, size): AdminProductPage!`, filtered by status, creator and search.
  - The model is **post-moderation**: content goes LIVE after processing, and admins take it down if needed. A pre-approval queue is out of scope.
  - So `approveProduct` is replaced by `restoreProduct(productId)`.
  - `takeDownProduct(productId, reason: String!)`: status UNPUBLISHED, plus `taken_down_at` and `takedown_reason`. The creator **cannot** republish a taken-down product (`republishProduct` checks for this). **Existing buyers keep access.** Revoking entitlements is a separate, explicit action and out of scope.
  - Migration `V6__admin_moderation.sql` adds those two columns plus the D7 table. Check the latest V number at implementation time and use the next free one.
- **D6 — Analytics (ADMIN-04).** `platformStats(days: Int = 30): PlatformStats` returns:
  - total users, creators, LIVE products
  - completed orders, gross sales, platform fees (all-time **and** last N days)
  - top 5 products by sales in the window

  Use plain aggregate SQL. Money is `Long` paise.
- **D7 — Admin audit log.** Table `admin_actions(id, admin_id, action, target_type, target_id, reason, created_at)`. It is append-only, enforced with the same trigger pattern as `payment_events` (V4). Every mutation in this phase writes one row in the same transaction. Add the query `adminActions(page, size)`.

## Frontend
- `/admin` routes behind `ProtectedRoute requiredRole="ADMIN"`, with a nav entry shown only to admins:
  - **Dashboard:** stat cards and a top-products table, with a window selector (7/30/90 days).
  - **Users:** searchable, filterable table. Suspend opens a modal with a required reason. Reactivate.
  - **Products:** filterable table. Take down opens a modal with a required reason. Restore. Link to the public page.
  - **Audit log:** paginated table.
- Creator dashboard: a taken-down product shows the reason and no Republish button.

## Acceptance criteria
1. A BUYER and a CREATOR get `ACCESS_DENIED` for **every** admin query and mutation (parameterised test).
2. `ADMIN_EMAILS` bootstrap grants ADMIN, and a second start doesn't duplicate it.
3. `adminUsers` search, filter and pagination work, and the counts are correct with a bounded query count.
4. **Suspension is immediate:**
   - an access token issued *before* suspension gets 401/403 on the next request
   - refresh fails
   - the user's viewer session tile fetch fails
   - after reactivation, a fresh login works
5. Suspending self or another admin is rejected.
6. Takedown hides the product from the marketplace, the buyer's library still shows it, the viewer still works, the creator can't republish, and restore brings it back.
7. `platformStats` numbers match a seeded fixture exactly.
8. Every admin mutation writes exactly one `admin_actions` row, and UPDATE/DELETE on that table raise.
9. Frontend tests: the admin nav is hidden for non-admins, and the suspend and take-down modals require a reason.

## Out of scope
- Content reporting by buyers.
- Payout approval (post-MVP).
- Impersonation.
- Bulk actions.
- Granting roles in the UI.

## Learning note
Create `docs/learning-notes/phase-08-admin-panel.md`. Headline topics:
- RBAC vs object-level authorisation
- privilege-escalation surface, and why admin bootstrap is config-only
- revoking stateless JWTs immediately (deny-list vs alternatives), and fail-open vs fail-closed
- post- vs pre-moderation
- append-only audit logs enforced in the DB
- aggregate queries for dashboards
