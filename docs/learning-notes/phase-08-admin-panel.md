# Phase 08 — Admin panel

> **Status:** Done
> **Built:** 2026-09-26
> **Requirement IDs covered:** ADMIN-01..04, AUTH-08 — from `docs/requirements.md`
> **Commits:** see `git log` on `auto/issue-8`

---

## 1. What we built, in plain English

**Before this phase:** every user on SecureLeaf, once registered, was equal. Nobody could see the whole marketplace at once, nobody could take down a product that shouldn't be there, and nobody could suspend an abusive account. The schema had stub fields (`allUsers`, `allProducts`, `suspendUser`) that hinted an admin panel was coming, but none of them worked, and — worse — a suspended user's already-issued login token kept working for up to fifteen minutes after the "suspension."

**After this phase:** a small number of accounts, named by email in server configuration (never through the API itself), can now sign in and see a `/admin` section. From there they can search and filter every user and every product, suspend a user (which ends their access *immediately*, not at their token's natural expiry), take a live product down with a written reason, watch platform-wide sales numbers, and read a permanent, tamper-proof log of every one of those actions.

The interesting engineering problem in this phase wasn't the CRUD — it's the same shape as every other admin panel you've ever used. The interesting problem was **immediacy under a stateless-token architecture**: SecureLeaf's access tokens are signed JWTs that the server never "looks up" to check they're still valid — that's the whole point of a JWT, it's self-contained. So how do you revoke one *right now*, without turning every single request back into a database lookup? The answer here is a small, purpose-built Redis set, and the trade-off it makes (fail open, not fail closed) is the centerpiece of this note.

---

## 2. Why it matters

A marketplace with real money and real uploaded content needs a way to react to abuse faster than "wait for the next deploy." A creator uploads something that shouldn't be sold; a buyer's account is compromised and starts spamming reviews; a payment dispute needs someone to look at platform-wide numbers instead of guessing. Every one of those is a today problem, not a someday problem, once real users exist.

It's built now, in Phase 8, rather than earlier, because it depends on almost everything before it: users, roles and JWTs (Phase 1), products and their lifecycle (Phases 2–3), orders and entitlements for the analytics (Phase 4), viewer sessions to end (Phase 5), and reviews (Phase 7) that an admin might one day need to moderate too (out of scope here, but the seams are visible). Building it earlier would have meant admin-specific special cases scattered through code that didn't exist yet.

If we skipped it: SecureLeaf would have no operational lever at all for handling a bad actor short of directly editing the production database — which is itself a much larger and much less auditable security risk than the feature this note describes.

---

## 3. New concepts introduced

### 3.1 RBAC vs. object-level authorization

**What it is:** Role-Based Access Control (RBAC) answers "is this *kind* of user allowed to call this *kind* of operation at all?" — a yes/no gate checked before the operation runs, independent of which specific record is involved. Object-level authorization answers a narrower question: "is this *specific* record this user's to touch?"

**The analogy:** RBAC is the badge reader on the building's front door — it only asks "does this badge open any door in this building?" Object-level authorization is the individual office door down the hall that additionally checks "is this *your* office?" A badge that opens the building doesn't automatically open every office.

**Why we needed it here:** every admin operation in this phase needs the RBAC gate (`hasRole('ADMIN')`) — that part is simple and total: a BUYER or CREATOR is refused before the method body ever runs. But two operations *also* need an object-level rule on top of the role check: `suspendUser` must refuse to suspend the caller's own account, or another admin's account. Role alone can't express that — an ADMIN calling `suspendUser` on another ADMIN's id passes the role check and would still succeed without a second, narrower rule.

**How it works:**
1. `@PreAuthorize("hasRole('ADMIN')")` on every admin resolver method — Spring Security's method interceptor runs *before* the method body, throwing `AccessDeniedException` if the caller's JWT doesn't carry `ROLE_ADMIN`.
2. Inside the service method, a second, specific check: `if (Objects.equals(adminId, targetUserId)) throw INVALID_INPUT` and `if (isAdmin(target)) throw INVALID_INPUT`.

**In our code:** `backend/src/main/java/com/secureleaf/admin/resolver/AdminUserResolver.java:31` (the role gate) and `backend/src/main/java/com/secureleaf/admin/service/AdminUserService.java:106` (the object-level rule).
```java
@MutationMapping
@PreAuthorize("hasRole('ADMIN')")
public AdminUserDto suspendUser(@Argument Long userId, @Argument String reason) {
    return adminUserService.suspendUser(getCurrentUserId(), userId, reason);
}
```
```java
if (Objects.equals(adminId, targetUserId)) {
    throw new BusinessException(ErrorCode.INVALID_INPUT, "You cannot suspend your own account.");
}
User target = userRepository.findWithRolesById(targetUserId)...
if (isAdmin(target)) {
    throw new BusinessException(ErrorCode.INVALID_INPUT, "You cannot suspend another admin.");
}
```

**What breaks without it:** without the role check, any logged-in buyer could call `suspendUser` on anyone. Without the object-level check, one disgruntled admin could suspend every other admin (including themselves by accident), locking the whole platform out of its own moderation tools with no one left able to reactivate anyone.

---

### 3.2 Privilege-escalation surface, and why admin bootstrap is config-only

**What it is:** "Privilege-escalation surface" means: every code path that *could* result in a user gaining more permissions than they started with. The smaller that surface, the fewer places a bug or a compromised account can turn into "now I'm an admin."

**The analogy:** if the only way to get a master key is for the building owner to physically hand you one, a thief who steals a tenant's key can only ever open that one tenant's door. If instead there were a "request a master key" button any tenant could press, the whole building's security now depends on that one button being bug-free forever.

**Why we needed it here:** the spec (D1) is explicit — there is **no GraphQL mutation that grants ADMIN**. The only way to become an admin is to have your email listed in the `ADMIN_EMAILS` environment variable, which only whoever controls the deployment's environment configuration can edit. This isn't a missing feature; it's a decision to keep the entire "how does someone become an admin" question outside the application's own request-handling code, where it can't be reached by a bug in that code.

**How it works:**
1. `admin.emails` (bound from `ADMIN_EMAILS`, comma-separated) is a `@ConfigurationProperties` record — read once, at startup, from the environment. See `AdminProperties.java`.
2. `AdminBootstrapRunner`, a Spring `ApplicationRunner`, runs once after the context starts and grants `ADMIN` to every *existing* user whose email matches.
3. `AuthService.register()` calls the same grant logic for *brand-new* registrations, so a configured email gets ADMIN immediately, not on the next restart.
4. Both paths funnel through one idempotent method — checking "does this user already have ADMIN" before adding it — so restarting the app twice, or registering twice, never inserts a duplicate role row.

**In our code:** `backend/src/main/java/com/secureleaf/admin/service/AdminBootstrapService.java:44`
```java
private void grantAdminRoleIfAbsent(User user) {
    boolean alreadyAdmin = user.getRoles().stream().anyMatch(r -> r.getRole() == Role.ADMIN);
    if (alreadyAdmin) {
        return;
    }
    UserRole adminRole = new UserRole();
    adminRole.setUser(user);
    adminRole.setRole(Role.ADMIN);
    user.getRoles().add(adminRole);
    userRepository.save(user);
}
```

**What breaks without it:** if there were instead a `grantAdmin(userId)` mutation guarded by, say, `hasRole('ADMIN')`, the security of the *entire application* would rest on that one `@PreAuthorize` annotation and everything upstream of it (the JWT signing key, the role-claim logic, every other check that decides who currently holds ADMIN) never having a bug. Config-only bootstrap doesn't remove risk — a leaked deployment credential is still bad — but it moves the risk to infrastructure access control, which is usually already more tightly gated (and audited) than application code paths.

---

### 3.3 Revoking a stateless JWT immediately: deny-list vs. the alternatives

**What it is:** a JWT access token is *stateless* — the server verifies its signature and reads its claims without ever looking anything up. That's what makes it fast, but it also means the server has no natural way to say "this specific token, which I already handed out, is no longer good," short of waiting for it to expire on its own.

**The analogy:** a JWT is like a concert wristband stamped at the door — the bouncer at every checkpoint just glances at the stamp, no phone call to the box office needed. That's fast, but if someone gets ejected from the venue, every other bouncer still lets the wristband through until the ink fades on its own. A deny-list is the venue radioing every checkpoint: "wristband #4471 — pull it, right now."

**Why we needed it here:** D4 requires that a suspended user is locked out *immediately*, not "within 15 minutes" (the access token's natural lifetime). SecureLeaf already had a mechanism for the read-heavy, less time-sensitive layer (the database's `account_status` column), but the request path already re-loads that row on every single request — so in practice, this project's existing per-request DB lookup (see the Gotchas table) already closes most of that gap by itself. What this phase adds is a second, faster, purpose-built layer for exactly this one question, and the choice of *how* to build it is the actual lesson.

**How it works — the options, and why we picked one:**

| Approach | How it works | Cost | Verdict |
|---|---|---|---|
| Do nothing, rely on token TTL | Wait for the access token to expire | Up to 15 minutes of continued access | Rejected — too slow for "immediate" |
| Load the user from the DB every request | Re-check `account_status` on every call | One DB round trip per request, forever, for every user, to answer a question that's "no" 99.99% of the time | Not chosen as the *new* mechanism (it happens to already exist here for other reasons) |
| Deny-list by `jti` (JWT ID) | One Redis key per *issued token*, checked and deleted individually | Unbounded growth — one key per token ever minted, needs its own expiry housekeeping matching the token's TTL | Rejected — more moving parts than the problem needs |
| **Deny-list by user id (chosen)** | One Redis **set**, `auth:suspended`, containing currently-suspended user ids | One `SADD`/`SREM`/`SISMEMBER` — O(1), self-cleaning (removed on reactivation) | **Chosen** — the smallest structure that answers exactly this question |

**In our code:** `backend/src/main/java/com/secureleaf/auth/security/SuspendedUsersService.java:1`
```java
public void suspend(Long userId) {
    redisTemplate.opsForSet().add(SUSPENDED_SET_KEY, userId.toString());
}

public boolean isSuspended(Long userId) {
    try {
        return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(SUSPENDED_SET_KEY, userId.toString()));
    } catch (Exception e) {
        log.error("Redis unavailable while checking auth:suspended for user id={} — failing open", userId, e);
        return false;
    }
}
```
Wired into `JwtAuthenticationFilter.java:64`, right after the existing DB-backed `account_status` check, on every authenticated request.

**What breaks without it:** exactly the bug this phase was asked to close — a stolen or still-open browser tab keeps a suspended user's access working until the token's natural 15-minute expiry, during which they can keep reading paid content, keep hitting the API, keep doing whatever got them suspended in the first place.

---

### 3.4 Fail-open vs. fail-closed

**What it is:** when a security check itself can't run (its dependency — here, Redis — is down), does the system default to **denying** the request (fail closed) or **allowing** it (fail open)?

**The analogy:** a fail-closed door lock defaults to *locked* if its power dies — safest for a bank vault, useless for a fire exit, where a powered-down lock defaulting to locked would trap people inside during the exact emergency it needs to let them out of. The right default depends entirely on which failure mode is worse.

**Why we needed it here:** this project's instinct everywhere else is fail-closed — `TileUrlSigner` rejects a tile request on *any* doubt about a signature, no exceptions. D4 deliberately breaks that pattern for the `auth:suspended` check, and the note is explicit that this needs defending, not hand-waving.

**How it works:** if the `SISMEMBER` call throws (Redis unreachable), `isSuspended()` catches it, logs at `ERROR`, and returns `false` — the request proceeds as if the user were not suspended.

**Why this is the right call here, specifically:** the reasoning is about what this check is actually *for*. It is not the only enforcement of suspension — the database's `account_status` column is *also* checked on every request (by the pre-existing `findWithRolesById` lookup in the same filter) and is *always* checked at login and token refresh, regardless of Redis. The Redis check exists purely to shave the worst case down from "up to 15 minutes" to "the very next request." If Redis has a five-minute outage, failing closed on this one specific, non-load-bearing check would mean **every single user on the platform** — not just the handful who happen to be suspended — gets logged out and blocked, because the code can't tell the difference between "you, specifically, are suspended" and "I can't currently prove you aren't." That's a self-inflicted platform-wide outage to protect against a problem (a suspended user gets up to 15 extra minutes of access, worst case) that is already bounded and already independently mitigated. Fail-closed is right when the check is the *only* thing standing between an attacker and the resource (that's `TileUrlSigner`'s situation: no DB check exists downstream of it). Fail-open is right when the check is a latency optimization layered on top of a boundary that's enforced elsewhere regardless.

**What breaks without this reasoning:** a security reviewer skimming the code and seeing "returns false when a check fails" without this context would (rightly!) flag it as a bug. Writing the reasoning down — right here, and in the code's own comment — is what turns a suspicious-looking line into a documented, defensible trade-off.

---

### 3.5 Post-moderation vs. pre-moderation

**What it is:** pre-moderation means content is reviewed *before* it becomes visible (a queue, an approval step). Post-moderation means content goes live immediately and is reviewed only if and when something flags it.

**The analogy:** pre-moderation is a bouncer checking IDs at the door before anyone gets in. Post-moderation is letting everyone in and occasionally walking the floor to eject anyone causing trouble.

**Why we needed it here:** SecureLeaf's existing pipeline (Phase 2) already takes a product straight from `PROCESSING` to `LIVE` the moment its PDF finishes converting — there was never an approval gate, and adding one now would be a much bigger, riskier change than this phase's actual scope. D5 is explicit that a pre-approval queue is *out of scope*; the only new lever is a takedown/restore pair that acts *after* the fact.

**How it works:** `takeDownProduct` only accepts a product that is currently `LIVE`, moves it to `UNPUBLISHED`, and stamps `taken_down_at`/`takedown_reason`. `restoreProduct` reverses exactly that — and *only* that: it refuses to "restore" a product a creator unpublished themselves (`takedown_reason IS NULL`), because that's not what it's for.

**In our code:** `backend/src/main/java/com/secureleaf/admin/service/AdminProductService.java:73`
```java
if (product.getStatus() != ProductStatus.LIVE) {
    throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
            "Product " + productId + " is not LIVE (currently " + product.getStatus() + ") — nothing to take down.");
}
```

**What breaks without it:** without the `taken_down_at`/`takedown_reason` pair specifically (as opposed to just reusing `UNPUBLISHED`), there would be no way to tell "the creator pulled this themselves" from "an admin removed this for a reason" — which matters a great deal to the creator dashboard (see 3.6) and to the audit trail.

---

### 3.6 Distinguishing "who unpublished this" without a new status

**What it is:** `UNPUBLISHED` already existed as a `Product` status (a creator's own `unpublishProduct`). Rather than invent a second status value for "unpublished by an admin," this phase reuses the same status and adds two nullable columns that are only ever both set, or both null, together.

**Why we needed it here:** a new enum value (say, `TAKEN_DOWN`) would have meant updating every place that already pattern-matches on `ProductStatus` (the marketplace's LIVE-only filter, the viewer's "still viewable if UNPUBLISHED" rule from Phase 5, the creator dashboard's status badge) to also handle a fifth case that behaves identically to `UNPUBLISHED` everywhere except one button. Reusing the status and adding a "why" pair keeps every existing `UNPUBLISHED`-shaped rule correct for free, and only the one rule that actually differs (can the creator republish it?) needs the new field.

**In our code:** the V7 migration enforces the pairing at the database level, not just in application code:
```sql
ALTER TABLE products ADD CONSTRAINT chk_products_takedown_pair
    CHECK ((taken_down_at IS NULL) = (takedown_reason IS NULL));
```
And `ProductRecoveryService.republishProduct` (Phase 6's code, touched by this phase) checks the new field:
```java
if (product.getTakedownReason() != null) {
    throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
            "Product " + productId + " was taken down by an admin and cannot be republished. "
                    + "An admin must restore it first.");
}
```

**What breaks without it:** a creator whose product an admin took down for cause could simply click "Republish" and undo the admin's decision — the takedown would have no teeth at all.

---

### 3.7 Append-only audit logs, enforced in the database

**What it is:** a table that the application can only ever `INSERT` into — never `UPDATE` or `DELETE` — with that restriction enforced by the database itself, not just by "nobody happens to call `.update()` on it in the code."

**The analogy:** a paper logbook bolted to the wall with a pen chained next to it. You can add a new entry, but there's no eraser, and tearing a page out is impossible to do quietly.

**Why we needed it here:** D7 requires exactly one `admin_actions` row per admin mutation, forever — a record that survives even a bug (or a malicious insider) that tries to edit history after the fact. This project already had exactly this pattern for `payment_events` (Phase 4); this phase reuses it verbatim for a second table.

**How it works:** a trigger function that unconditionally raises an exception on `UPDATE` or `DELETE`, attached to the table with `BEFORE UPDATE OR DELETE`.

**In our code:** `backend/src/main/resources/db/migration/V7__admin_moderation.sql:29`
```sql
CREATE FUNCTION forbid_admin_action_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'admin_actions is append-only: % is not allowed', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_admin_actions_append_only
    BEFORE UPDATE OR DELETE ON admin_actions
    FOR EACH ROW EXECUTE FUNCTION forbid_admin_action_mutation();
```
And the write path is centralized in one place, `AdminAuditService.record()`, called with `Propagation.MANDATORY` — so calling it outside an existing transaction is itself a bug that fails loudly, guaranteeing the audit row and the change it describes commit (or roll back) together.

**What breaks without it:** an application-level "please don't call `.save()` twice" convention is only as strong as everyone remembering it forever. A future refactor, or a compromised admin account with direct database access, could quietly rewrite history. The trigger makes that structurally impossible instead of merely discouraged.

---

### 3.8 Aggregate queries for a dashboard, without pulling rows into the JVM

**What it is:** computing a `COUNT`, `SUM`, or `GROUP BY` *in the database*, in one round trip, instead of fetching every matching row into application memory and looping over it there.

**The analogy:** asking a librarian "how many books on this shelf are red?" gets you one number back. Asking for every book on the shelf so you can count the red ones yourself gets you the same answer, but only after carrying the whole shelf to your desk first.

**Why we needed it here:** `platformStats` needs totals across the *entire* `orders`/`order_items`/`users`/`products` tables — potentially millions of rows in production. D6 is explicit: "use plain aggregate SQL."

**How it works:** `AdminAnalyticsRepository` issues native SQL like:
```sql
SELECT COUNT(DISTINCT o.id), COALESCE(SUM(oi.price_paise), 0), COALESCE(SUM(oi.platform_fee_paise), 0)
FROM orders o JOIN order_items oi ON oi.order_id = o.id
WHERE o.status = 'COMPLETED'
```
Postgres computes the three numbers server-side and hands back exactly one row; the JVM never sees an individual order.

**In our code:** `backend/src/main/java/com/secureleaf/admin/repository/AdminAnalyticsRepository.java:45`

**What breaks without it:** loading every completed order (and its line items) into the JVM just to sum a column is both slower (network transfer of rows you're about to discard) and unbounded in memory — the query's cost grows with the *table's* size, not with the size of the answer.

---

## 4. Best practices applied

| Practice | What we did | Why it matters | Where |
|---|---|---|---|
| Defense in depth (role + object-level) | `@PreAuthorize("hasRole('ADMIN')")` on every resolver, plus a specific self/admin guard inside the service | A role check alone can't express "not yourself, not another admin" | `AdminUserService.java:106` |
| Two-step id-paging | id-page + count (native SQL, no collection join) → `@EntityGraph findAllByIdIn` → re-order in Java | Avoids Hibernate's HHH000104 in-memory-pagination fallback for `User.roles`/`Product.tags` — same recipe as Phase 3's marketplace search | `AdminUserQueryRepository.java`, `AdminProductQueryRepository.java` |
| Bounded query count, batched aggregates | `adminUsers` always runs exactly 5 of its own queries (plus 1 for authenticating the caller), regardless of page size | No N+1 on `productCount`/`purchaseCount` per row | `AdminUserService.java:60`, proven by `AdminUserManagementIT` |
| Config-only privilege escalation | No mutation grants ADMIN; only `ADMIN_EMAILS` + a restart/registration | Removes an entire class of "who can call this and how do I know the check is airtight" bug | `AdminBootstrapService.java` |
| Append-only audit trail | DB trigger rejects UPDATE/DELETE on `admin_actions`, same pattern as `payment_events` | Structural, not conventional, tamper-resistance | `V7__admin_moderation.sql` |
| Guarded state transitions | `takeDownProduct` only from LIVE; `restoreProduct` only if `takedown_reason` is set | Same "typed `INVALID_STATE_TRANSITION`, never a silent no-op" pattern as Phase 6 | `AdminProductService.java` |
| Fail-open documented as a deliberate trade-off, not an oversight | `SuspendedUsersService.isSuspended` catches, logs `ERROR`, returns `false` | A reviewer needs the *reasoning*, not just the behavior | `SuspendedUsersService.java` |
| Constraint enforced in the database, not just Java | `chk_products_takedown_pair` CHECK constraint | A future code path that sets one column without the other fails at INSERT, not silently | `V7__admin_moderation.sql` |

---

## 5. What does what — file map

| File | Responsibility |
|---|---|
| `backend/.../admin/AdminProperties.java` | Binds `admin.emails` (`ADMIN_EMAILS`) — the entire config surface for who can become an admin |
| `backend/.../admin/AdminBootstrapRunner.java` | Runs once at startup, grants ADMIN to existing users matching config |
| `backend/.../admin/service/AdminBootstrapService.java` | The one idempotent grant method both the runner and `AuthService.register()` call |
| `backend/.../auth/security/SuspendedUsersService.java` | Owns the `auth:suspended` Redis set — suspend/reactivate/check, fail-open on Redis errors |
| `backend/.../auth/security/JwtAuthenticationFilter.java` | Per-request: DB `account_status` check (pre-existing) + the new `auth:suspended` check |
| `backend/.../admin/service/AdminUserService.java` | `adminUsers` search, `suspendUser`/`reactivateUser` — revokes refresh tokens, ends viewer sessions, updates the Redis set |
| `backend/.../admin/service/AdminProductService.java` | `adminProducts` search, `takeDownProduct`/`restoreProduct` |
| `backend/.../admin/service/AdminAnalyticsService.java` + `AdminAnalyticsRepository.java` | `platformStats` — all-time and windowed aggregates, top-5 products |
| `backend/.../admin/service/AdminAuditService.java` | The only writer of `AdminAction` rows; also serves `adminActions` reads |
| `backend/.../admin/entity/AdminAction.java` | The append-only audit row entity |
| `backend/.../viewer/service/ViewerSessionService.java#endAllSessionsForUser` | Ends every open viewer session for a suspended user, across every product |
| `backend/.../marketplace/entity/Product.java` | `takenDownAt`/`takedownReason` — the admin-takedown marker |
| `backend/.../creator/service/ProductRecoveryService.java#republishProduct` | Refuses to republish a product an admin took down |
| `frontend/src/pages/admin/*.tsx` | Dashboard, Users, Products, Audit log pages |
| `frontend/src/components/admin/ReasonPromptDialog.tsx` | The reason-required modal shared by suspend and take-down |
| `frontend/src/components/admin/AdminNav.tsx` | Tab nav across the four admin pages |

**Request trace — `suspendUser`:**
1. Browser calls `suspendUser(userId, reason)` with the admin's JWT →
2. `AdminUserResolver` — `@PreAuthorize("hasRole('ADMIN')")` passes; extracts the caller's id →
3. `AdminUserService.suspendUser` — validates the reason, refuses self/another-admin, sets `account_status = SUSPENDED`, calls `RefreshTokenRepository.revokeAllForUser`, calls `ViewerSessionService.endAllSessionsForUser` (ends every open session, deletes each Redis active-session key), calls `SuspendedUsersService.suspend` (adds to `auth:suspended`) →
4. `AdminAuditService.record(...)` — one `admin_actions` row, same transaction →
5. Transaction commits — every change above lands together or not at all →
6. Next request from that user: `JwtAuthenticationFilter` sees `account_status != ACTIVE` (or, redundantly, `auth:suspended` membership) and treats the request as unauthenticated.

---

## 6. Design decisions and trade-offs

### Decision: a Redis *set* keyed by user id, not a deny-list keyed by token `jti`

- **Alternatives considered:** (a) do nothing new, rely on the 15-minute token TTL; (b) a deny-list of individual token ids (`jti`), one Redis key per token ever issued; (c) a set of currently-suspended user ids.
- **Why we chose this:** the question we need to answer is always "is *this user*, right now, suspended" — never "was this *specific token* individually revoked." A user-keyed set answers exactly that, in one `SISMEMBER`, with exactly one entry per suspended user (not one per token they've ever held), and it self-cleans on reactivation (`SREM`).
- **What we gave up:** this can't express "revoke this one specific token but leave the user's other sessions alone" — every access token for a suspended user stops working, which happens to be exactly what suspension means here, so nothing was actually lost for this use case.
- **When we would revisit:** if a future feature needed per-device/per-session revocation (e.g. "log out this one browser, not all of them") independent of account suspension, that would need the `jti` deny-list shape instead — a genuinely different question.

### Decision: fail-open on the `auth:suspended` check specifically

- **Alternatives considered:** fail closed (deny the request whenever Redis can't answer).
- **Why we chose this:** this specific check is one of *two* layers enforcing suspension (the DB `account_status` check is the other, and is unconditional), so failing open here only ever widens the worst case back to "up to 15 minutes," never past it. Failing closed would turn a Redis blip into a platform-wide outage for a check whose entire job is shaving a few minutes off an already-bounded window.
- **What we gave up:** a truly worst-case window (Redis down for the whole 15 minutes right after a suspension) is not fully closed by this layer alone — though the DB check still applies on every request regardless, in this codebase's current implementation.
- **When we would revisit:** if the per-request DB `account_status` lookup were ever removed for performance reasons (trusting JWT claims instead), this Redis check would become the *only* enforcement layer, and fail-open would need to become fail-closed at that point — this is flagged explicitly in the Gotchas table below.

### Decision: reuse `UNPUBLISHED` + a nullable reason pair, not a new `ProductStatus` value

- **Alternatives considered:** a distinct `TAKEN_DOWN` enum value.
- **Why we chose this:** every existing rule keyed on `ProductStatus` (marketplace visibility, viewer access, dashboard badges) is already correct for `UNPUBLISHED`, and a takedown should behave identically everywhere except the one rule that's actually new (republish). Reusing the status means zero of that existing logic needed to change.
- **What we gave up:** the two states are now distinguishable only by an extra nullable field, not by status alone — a query that filters by status without also checking `takedown_reason` can't tell them apart. In practice every place that needs to tell them apart already does check it.
- **When we would revisit:** if a *third* meaningfully different "why is this UNPUBLISHED" reason appeared, a dedicated status enum (or a small reason-code enum) would scale better than accumulating more nullable columns.

---

## 7. Interview questions

### Beginner
**Q: What's the difference between authentication and authorization, and where does RBAC fit?**
A: Authentication is "who are you" — proving identity, usually via a password or token. Authorization is "what are you allowed to do." RBAC is one *style* of authorization: you're allowed to do things based on a role you hold (like ADMIN), rather than anything specific to the record you're touching.

**Q: Why can't a plain role check alone stop an admin from suspending another admin?**
A: Because `hasRole('ADMIN')` only asks "is the caller an admin" — it says nothing about *who the target* is. You need a second check, inside the business logic, that looks at the specific record being acted on.

### Intermediate
**Q: A JWT is supposed to be stateless. How do you revoke one before it expires?**
A: You can't truly "revoke" a stateless JWT itself — you add a *side channel* the verifying code also checks. Here that's a Redis set of currently-suspended user ids, checked on every request alongside the JWT's signature. The token itself is never touched; the server just also asks "is this token's subject on the blocked list."

**Q: Why did you choose a Redis set instead of just checking the database on every request?**
A: In this project, the database check already existed independently (for other reasons) and does answer the question correctly — but it costs a DB round trip on every single authenticated request, forever, to answer a question that's "no" for the overwhelming majority of users. A Redis `SISMEMBER` is O(1) and only holds one entry per currently-suspended user, so it's a much cheaper way to answer the same question fast.

**Q: What does "fail open" mean, and when is it the wrong choice?**
A: Fail open means: if the check itself can't run, default to *allowing* the request. It's wrong whenever the check is the *only* thing standing between a bad actor and a protected resource — there, you fail closed, because letting an attacker through is worse than an outage. It's defensible when the check is a redundant, latency-focused layer on top of a boundary that's still enforced elsewhere.

### Advanced / follow-up probes
**Q: You said the database check happens on every request "for other reasons." What does that imply about whether the Redis layer is actually load-bearing today?**
A: It implies that, as currently written, the Redis check is *not* strictly necessary for correctness — the per-request DB lookup already closes the gap by itself. The Redis layer earns its keep as a cheaper, purpose-built answer to the same narrow question, and as insurance: if that DB lookup is ever optimized away (a very common production change, to cut a DB round trip off every request), the Redis check is what would then become the only thing standing between a suspended user and continued access — at which point its fail-open default would need to be revisited and likely flipped to fail-closed.

**Q: Why enforce the audit log's append-only property with a database trigger instead of just not writing code that updates it?**
A: Because "nobody writes code that does X" is a social convention, not a guarantee — it survives exactly until someone forgets, refactors carelessly, or a compromised credential bypasses the application entirely and talks to the database directly. A trigger makes the violation impossible at the one layer every write must pass through, regardless of which code (or which credential) is doing the writing.

**Q: Why is `takeDownProduct` restricted to only starting from LIVE, instead of accepting any current status?**
A: Because "take down" is a specific, meaningful transition — reversing a decision that already made the product publicly visible. Allowing it from, say, DRAFT or PROCESSING would let it silently do nothing useful (there's no "takedown" of something nobody could see yet) while still writing a misleading audit-log entry claiming an admin took action. Guarding the starting state keeps every audit-log row honestly describing something that actually changed.

### "Tell me about a bug you fixed"
**Q: Tell me about a bug you found while building this.**
A: While writing the integration tests, I hit an `INTERNAL_SERVER_ERROR` whenever an admin filtered users by role or status. The root cause: my native SQL queries compared a Postgres enum column (`account_status`, `user_role`) against a bound String parameter with no cast, which Postgres rejects outright — `enum_column = text` isn't a valid comparison. My first fix attempt, `:status::account_status`, made things *worse*: Hibernate's named-parameter parser turned out to be greedy and swallowed the whole `::type` suffix into the parameter's *name*, so it looked for a parameter literally called `status::account_status` and never found it. The actual fix was the more verbose, unambiguous `CAST(:status AS account_status)` syntax, which Hibernate parses correctly because the parameter name ends cleanly at the closing space. The lesson: when a database needs an explicit type for a bound parameter in a native query, prefer `CAST(...)` over the terser `::` operator — it's not just a style preference, the terser form can silently break the ORM's own parameter parsing in ways that have nothing to do with the database at all.

---

## 8. Gotchas and bugs we hit

| Symptom | Root cause | Fix | Lesson |
|---|---|---|---|
| `adminUsers(filter: {role: CREATOR})` returned `INTERNAL_SERVER_ERROR` | Native SQL compared a Postgres enum column to an untyped bound parameter (`ur.role = :role`) — valid in JPQL (Hibernate infers the column type) but not in a raw native query | Explicit `CAST(:role AS user_role)` | Native queries lose the type inference JPQL gives you for free — enum comparisons need an explicit cast |
| The same fix attempt, written as `:role::user_role`, threw `UnknownParameterException: No parameter named ':role'` | Hibernate's named-parameter parser is greedy across `::` — it treated the whole `role::user_role` as the parameter's name, not just `role` | Use `CAST(:role AS user_role)` instead of the `::` shorthand | The terser Postgres cast syntax and the ORM's parameter parser don't always agree on where a parameter name ends |
| An unrelated test (`ProductRecoveryIT`) started intermittently failing — a background pipeline job that should finish `LIVE` ended up `FAILED` | An early draft of the bootstrap test used `@DynamicPropertySource` to set a distinct `admin.emails` value, which Spring caches as a *second*, separate application context. That context's own `@Scheduled` `ProcessingJobWorker` poller ran concurrently with the default context's poller, and both raced for the same `processing_jobs` row against one shared Testcontainers Postgres — the loser's `InMemoryStorageService` (a *different* bean instance, scoped to its own context) didn't have the uploaded PDF bytes, so its attempt failed | Replaced the integration test with pure Mockito unit tests of `AdminBootstrapService` (no Spring context at all) | Spring Test caches contexts *per distinct property set* — any new `@DynamicPropertySource`/`@TestPropertySource` value silently spins up a second, fully-live application, including its own scheduled jobs, sharing the same singleton Testcontainers database. If a test doesn't need a real context, don't ask for one |
| A test asserting `refreshToken` fails with `ACCOUNT_SUSPENDED` after a suspension actually got *no error at all* on the first attempt, then `TOKEN_REUSE` once the assertion was corrected | `suspendUser` already revokes every refresh token (D4's own first bullet) — so by the time the test's captured refresh token was replayed, `AuthService.refreshToken`'s *reuse-detection* branch (built for stolen-token detection, Phase 1) fired first, since a revoked-but-never-rotated token is exactly what reuse detection is designed to catch | Corrected the test's expected error code to `TOKEN_REUSE` | Two independently-built safety nets can both be "correct" and still surprise you about *which one* fires first — trace the actual code path instead of assuming the mechanism you were thinking about when you wrote the test |
| A "bounded query count" test expected 5 statements for `adminUsers` and got 6 | `JwtAuthenticationFilter` re-loads the calling user (with roles) on *every* authenticated request, to authenticate the caller — a cost every resolver pays, not specific to `adminUsers`'s own logic | Adjusted the expected count to 6, with a comment attributing the extra one | When asserting an exact statement count, remember the request has to authenticate *before* your resolver ever runs — that's a real, constant cost worth naming explicitly, not folding silently into "whatever the number turns out to be" |

---

## 9. New vocabulary

| Term | One-line meaning |
|---|---|
| RBAC | Role-Based Access Control — permission based on a role, not the specific record |
| Object-level authorization | A narrower check: is *this specific record* the caller's to act on |
| Privilege-escalation surface | Every code path that could let a user end up with more permission than they started with |
| Deny-list (token revocation) | A side list of "these are no longer good," checked alongside an otherwise-stateless credential |
| Fail open / fail closed | On error, allow the request through / deny it — the right default depends on whether the check is the *only* boundary or a redundant layer |
| Post-moderation | Content goes live first; review (and possible takedown) happens after, only if flagged |
| Pre-moderation | Content is reviewed *before* it becomes visible |
| Append-only table | A table only ever `INSERT`ed into — `UPDATE`/`DELETE` rejected, usually by a DB trigger |
| Guarded state transition | A mutation that checks the record's current state is a legal starting point before changing it |
| `Propagation.MANDATORY` | A `@Transactional` method that must join an existing transaction or throw — used to guarantee an audit row commits atomically with the change it describes |
| Aggregate query | `COUNT`/`SUM`/`GROUP BY` computed by the database in one round trip, never by looping over fetched rows in application code |

---

## 10. If I had to defend this in a code review

The strongest points: the privilege-escalation surface for *becoming* an admin is genuinely tiny (config only, no API path at all), the audit log is tamper-resistant at the database layer rather than by convention, and every acceptance criterion in the spec — including the trickier "immediate suspension across four different surfaces" one — has a dedicated integration test proving it, not just an assumption that the code is probably right.

The weakest point, and the one I'd fix first given more time: the `auth:suspended` Redis check's fail-open behavior is only *safe* because of an implementation detail elsewhere in the codebase — the fact that `JwtAuthenticationFilter` already re-loads the user from the database on every request, for reasons that predate this phase and have nothing to do with suspension. If a future performance pass ever removes that per-request DB lookup (a very ordinary optimization — trusting the JWT's claims instead of re-verifying them against the database every time), the Redis check silently becomes the *only* thing enforcing suspension, and its current fail-open default would then need to flip to fail-closed without anyone necessarily remembering to check for that. I'd want either a test that pins this cross-cutting assumption explicitly (so removing the DB lookup fails loudly, not silently), or a code comment strong enough that nobody makes that change without reading this note first — which is exactly what I've added at both call sites, but a comment is not the same guarantee as a test.
