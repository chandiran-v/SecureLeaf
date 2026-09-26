# Phase 07 — Reviews & ratings + password reset

> **Status:** Done
> **Built:** 2026-09-26
> **Requirement IDs covered:** REV-01..04, MARKET-03, AUTH-07 (`docs/requirements.md`)
> **Design doc:** [`docs/phases/phase-07-reviews-password-reset.md`](../phases/phase-07-reviews-password-reset.md) — decisions D1–D10
> **Commits:** `auto/issue-7` — see `git log` on that branch

---

## How to study this note

Read §1–2 for the shape of the problem, then §3 slowly. §3.1 (the concurrency-safe aggregate) and §3.3 (the privacy leak) are the two an interviewer is most likely to dig into. §6 records the trade-offs; §10 names this phase's weakest point on purpose.

---

## 1. What we built, in plain English

Two unrelated-looking features shipped together because both were "the schema already has the columns, nothing reads or writes them yet": reviews, and password reset.

**Reviews.** The `reviews` table, and `products.average_rating`/`review_count`, existed since the very first migration (Phase 0). The GraphQL schema even *declared* `submitReview` and `productReviews`. But nothing implemented them — there was no resolver at all — and the one thing that *did* exist, `type Review { buyer: User! }`, would have handed out the reviewer's email address to anyone who could see a product's reviews, the moment someone did wire it up. This phase builds the real thing: an entitled buyer can rate 1–5 stars and leave text, edit that review any time (it's an upsert, not a growing list of reviews-per-buyer), or delete it; the product's average and count stay exactly correct even when two buyers review the same product in the same instant; and the reviewer is shown by name only, never by email.

**Password reset.** `users.reset_token_hash`/`reset_token_expires_at` existed, unused, since Phase 0 too. Before this phase, a buyer who forgot their password had no way back into their account short of asking an admin (which doesn't exist yet either). Now: `requestPasswordReset(email)` always says "done," whether or not that email has an account, emails a real reset link only when it does (and something different — "you sign in with Google" — for a Google-only account), and `resetPassword(token, newPassword)` consumes that link exactly once, revoking every session the account was logged into so a stolen laptop can't ride along on the old password forever.

**Before this phase:** the reviews UI had nothing to call; a rating field on a product page was permanently blank; a forgotten password was a dead end.

**After this phase:** the product detail page shows a rating histogram, a paginated review list, and — for anyone who's actually bought the thing — a star-input review form; the marketplace can filter to "4★ & up"; and "Forgot password?" on the login page leads all the way to a working, secure reset flow.

---

## 2. Why it matters

- **A denormalized `average_rating` column is a promise:** it must always equal `AVG(rating)` over that product's reviews, or every page that shows it is lying. The moment two people can write to the same product's reviews at once, keeping that promise honestly is the actual engineering problem — not the CRUD around a review row.
- **`Review.buyer: User!` was a live privacy bug waiting for a resolver.** `User` carries `email`. The instant `submitReview`/`productReviews` got wired up without fixing the type first, *every* review on the marketplace would have leaked its author's email to any visitor who selected that field. Catching this before shipping the resolver, not after, is exactly what a security-minded code review is for.
- **User enumeration is a real, exploitable bug class**, not academic: if "forgot password" behaves differently for a known vs. unknown email, an attacker can feed it a mailing list and learn which addresses have accounts on your site — a data breach with none of the usual breach mechanics.
- **A password reset that doesn't revoke sessions defeats its own purpose.** The whole reason to reset a password is usually "I think someone else has it" — if their already-open session (stolen refresh token) keeps working after the reset, the reset accomplished nothing.
- **What would break if we skipped the hard parts:** no row lock on the aggregate update → two concurrent reviews silently produce a `review_count` that's off by one, forever, with no error to notice it by. No enumeration-safe response → a public API for "does this email have an account." No rate limit → one attacker can flood a stranger's inbox with reset emails. No session revocation on reset → a "security" feature that doesn't actually improve security.

---

## 3. New concepts introduced

### 3.1 Denormalized aggregates, and the lost-update anomaly they invite

**What it is:** `products.average_rating`/`review_count` are *denormalized* — derivable from the `reviews` table (`AVG(rating)`, `COUNT(*)` grouped by `product_id`), but stored again on `products` so a product-listing query never has to join and aggregate `reviews` just to show a star rating. The cost of denormalizing anything is that now two things can disagree; keeping them in sync is the code we have to write on purpose.

**The analogy:** two people editing the same shared spreadsheet cell, each looking at the value, doing math in their head, and typing a new number — without either one seeing that the other is also mid-edit. Whoever saves last wins, and it's easy for the saved number to reflect only *one* of the two edits, not both.

**Why we needed it here (REV-04):** picture two different buyers submitting a review for the *same* product at almost the same instant. If the code read the current average, computed a new one in Java, and wrote it back — two separate read-then-write cycles, racing — the second write can silently overwrite the effect of the first. This is the classic **lost-update anomaly**: nothing crashes, no exception fires, the `review_count` is just wrong, quietly, forever.

**How it works — the three-step recipe, all in one transaction:**
1. **Lock the product row:** `SELECT ... FROM products WHERE id = ? FOR UPDATE`. Any other transaction trying to do the same thing for the *same* product now blocks until this one commits. Different products lock different rows — this never serializes unrelated writers.
2. **Write the review** (insert for a new review, update for an edit, delete for a removal) — still holding that lock.
3. **Recompute the aggregate from scratch**, in the database, in one statement: `UPDATE products SET average_rating = (SELECT ROUND(AVG(rating)::numeric, 2) FROM reviews WHERE product_id = ?), review_count = (SELECT COUNT(*) FROM reviews WHERE product_id = ?) WHERE id = ?`.

Because step 3 re-derives the number from the actual rows rather than nudging a running total, an edit or a delete is exactly as correct as a brand-new review — there's no separate "subtract the old rating, add the new one" arithmetic that could itself drift.

**In our code:** [`ProductRepository.java`](../../backend/src/main/java/com/secureleaf/marketplace/repository/ProductRepository.java) and [`ReviewService.java:66-104`](../../backend/src/main/java/com/secureleaf/marketplace/service/ReviewService.java#L66)
```java
// D4 step 1 — serialise every writer for THIS product before touching reviews/aggregate.
productRepository.findByIdForUpdate(productId);

Review review = reviewRepository.findByBuyerIdAndProductId(buyerId, productId)
        .orElseGet(() -> { /* new Review, product + buyer set */ });
review.setRating(rating.shortValue());
review.setReviewText(cleanedText);
review = reviewRepository.save(review);           // D4 step 2

productRepository.recalculateReviewAggregate(productId);   // D4 step 3
```

**Why not just bump `@Version` and let JPA's optimistic lock catch the race?** `Product` already has `@Version private Long version` for normal edits. But optimistic locking's answer to a conflict is "fail, and make the caller retry" — here that would mean one of the two buyers' `submitReview` calls throwing an `OptimisticLockException` for no reason a buyer could understand ("I just want to leave a review — why did that fail?"). The pessimistic lock in step 1 avoids the conflict outright instead of detecting and rejecting it after the fact. The native, `@Modifying` bulk `UPDATE` in step 3 deliberately **bypasses** the JPA persistence context and `@Version` entirely — the same choice this codebase already made for `Product.totalSales` (`ProductRepository.incrementTotalSales`, Phase 4): it's one self-contained SQL statement, not a read-modify-write through the entity, so there's nothing for optimistic locking to protect and nothing to accidentally increment by writing a stale in-memory copy back.

**The alternatives, and why they lost:**
| Alternative | What goes wrong |
|---|---|
| Incremental formula (`avg = (avg*n + newRating) / (n+1)`) | Correct for a brand-new review; wrong the moment a review is *edited* or *deleted* — there's no clean incremental formula for "un-average" one value out |
| Nightly batch recompute | Correct eventually, wrong (stale) for up to 24 hours — a buyer who just left the first 5★ review would see "no reviews yet" until the batch runs |
| `SERIALIZABLE` isolation for the whole transaction | Technically correct, but Postgres implements it by aborting one of two conflicting transactions and asking it to retry — under real concurrent load on a popular product, that's a **retry storm**, not a clean fix |

**What breaks without it:** `review_count` silently drifts from `SELECT COUNT(*) FROM reviews WHERE product_id = ?` — a fact nobody notices until a support ticket says "the rating looks wrong," with no error log pointing at why. `ReviewIT.concurrentReviewsFromDifferentBuyers_bothLand_andAverageIsExact` proves the fix with two real threads and a `CountDownLatch` forcing them to race, not just a single-threaded happy path.

---

### 3.2 Upserts

**What it is:** "INSERT if it doesn't exist, UPDATE if it does" — one operation, one row, no separate "check, then branch" logic the caller has to get right.

**The analogy:** setting a thermostat. You don't check whether it currently has a temperature set before "setting" it — you just set it, and whatever was there before is simply replaced.

**Why we needed it here (D2):** `uq_reviews_buyer_product UNIQUE (buyer_id, product_id)` means a buyer can have *at most one* review per product. `submitReview` calling it a second time isn't a new review — the product page's design (one review form per buyer, with Edit/Delete) needs it to update the existing row in place.

**How it works:** [`ReviewService.java:74-83`](../../backend/src/main/java/com/secureleaf/marketplace/service/ReviewService.java#L74)
```java
Review review = reviewRepository.findByBuyerIdAndProductId(buyerId, productId)
        .orElseGet(() -> {
            Review created = new Review();
            created.setProduct(product);
            created.setBuyer(entitlement.getBuyer());
            return created;
        });
review.setRating(rating.shortValue());
review.setReviewText(cleanedText);
```
Read-then-branch, not a database-level `ON CONFLICT DO UPDATE` — this codebase already reads-then-branches for its other upsert (`AuthService.becomeCreator`'s `CreatorProfile`, Phase 1), so this matches an established, teachable pattern rather than introducing a second one. `updated_at` bumps automatically via `BaseEntity`'s `@LastModifiedDate` on the same save — no extra code needed to know a review was edited vs. freshly created.

**The final guard, not the only one:** the unique constraint is still there as a backstop — if a genuine race slipped past the row lock somehow (it can't, given §3.1's lock, but defense in depth doesn't assume its own first layer is infallible), the resulting `DataIntegrityViolationException` is caught and turned into a clean `DuplicateResourceException` rather than a raw 500, the same pattern `AuthService.register` already uses for a duplicate email (Phase 1).

**What breaks without it:** without the unique constraint as a backstop, a bug elsewhere that skipped the lock would silently create two review rows for the same buyer+product, and the aggregate would double-count one buyer's opinion.

---

### 3.3 Data minimisation — the privacy leak we caught in the GraphQL type

**What it is:** *data minimisation* is the principle that an API should return exactly the fields a feature needs, and not one field more — even if the extra field feels "free" because the object already has it.

**The analogy:** a name badge at a conference shows your name and company, not your home address — even though the organizer's registration database has your home address sitting right there, one column over.

**Why we needed it here (D5):** the pre-existing schema had `type Review { buyer: User! }`. `User` is the same type `me` returns, and it carries `email`. Nothing about *reviews* needs a reviewer's email — the feature is "show who left this opinion," which needs a name, full stop. The bug wasn't a typo; it was reusing a type that happened to be lying around, without asking "does this specific field belong here."

**How it works:** [`schema.graphqls`](../../backend/src/main/resources/graphql/schema.graphqls)
```graphql
type Review {
    id: ID!
    reviewer: ReviewerSummary!
    rating: Int!
    reviewText: String
    createdAt: DateTime!
    updatedAt: DateTime!
}

# D5 — the reviewer's public-safe projection. Deliberately narrower than CreatorSummary
# (no id): a review is displayed with a name only, never with anything that could be
# used to look the buyer up.
type ReviewerSummary {
    displayName: String!
}
```
This mirrors a decision this codebase already made once, for a different relationship: `Product.creator: CreatorSummary!` (not `User!`) since Phase 3, for exactly the same reason. `ReviewerSummary` is deliberately narrower even than `CreatorSummary` — no `id` either, since nothing about displaying a review needs to let a client look the reviewer up by id.

The fix isn't "hide the field at the resolver level" — it's that the **schema itself** makes the leak structurally impossible: there is no `email` field on `ReviewerSummary` for a client to even ask for. A query that tries fails **GraphQL schema validation** before any resolver code runs at all, which is testable directly:
```graphql
query($productId: ID!) {
  productReviews(productId: $productId) { content { reviewer { displayName email } } }
}
```
`ReviewIT.reviewerSummary_hasNoEmailField` sends exactly this and asserts the response is a validation error mentioning `email` — proving the leak is closed at the type level, not just "currently doesn't happen to be requested."

**What breaks without it:** an API surface where "can I fetch this field" and "should this feature expose this field" are two different questions is one bad copy-paste away from a real leak — and unlike a runtime authorization bug, a type-level leak like this one is *silent*: no error, no denied request, just data that shouldn't be visible, visible.

---

### 3.4 User enumeration

**What it is:** learning which accounts exist on a system by noticing that its responses differ for "this email has an account" vs. "this email doesn't" — even when neither response contains the word "yes" or "no."

**The analogy:** a locked building where a badge reader beeps green for a valid badge and red for an invalid one. Even without ever opening the door, you've learned something: which badges are real. The same building with **no light at all**, always exactly the same silent behavior for any badge, leaks nothing.

**Why we needed it here (D8):** if `requestPasswordReset("victim@example.com")` returned `true` and `requestPasswordReset("random-guess@example.com")` returned `false`, an attacker with a list of a million email addresses could silently learn exactly which ones are SecureLeaf accounts — a real privacy/security exposure, and a classic building block for targeted phishing or credential-stuffing.

**How it works:** the mutation is typed `Boolean!` and its implementation always resolves `true`, on every path — unknown email, known email, rate-limited, Google-only account. The *only* externally observable difference between "known account, email sent" and "unknown email, nothing happened" is meant to be nothing at all from the API's shape. (The honest gap — response *timing* — is called out in §6's design-decision box, not swept under the rug.)

**In our code:** [`PasswordResetService.java:65-90`](../../backend/src/main/java/com/secureleaf/auth/service/PasswordResetService.java#L65)
```java
Optional<User> maybeUser = userRepository.findByEmail(normalizedEmail);
if (maybeUser.isEmpty()) {
    log.info("Password reset requested for an email with no account — no-op (enumeration-safe)");
    return true;
}
```
The frontend has to hold up its end of this too — `ForgotPasswordPage` shows the exact same "if an account exists, we've sent a link" message whether the mutation reported success *or threw* (a network blip is caught and swallowed before the generic message is shown either way). A UI that only shows the friendly message on success, and a different one on error, would silently reopen the same hole the backend just closed. `ForgotPasswordPage.test.tsx` asserts both paths render identical text.

**What breaks without it:** a "forgot password" feature that's simultaneously a working password-recovery flow *and* a public API for probing which emails have accounts — two features in one endpoint, only one of which is intentional.

---

### 3.5 Reset-token hashing and single use

**What it is:** the raw reset token is a long random string, emailed to the user. The database never stores that string — only its SHA-256 hash. And the moment a token is used successfully, it's deleted, so replaying the same link a second time fails.

**The analogy:** a coat-check ticket. The cloakroom keeps a stub matching what they'll accept, not a photograph of every ticket they've ever issued (so a break-in at the cloakroom doesn't hand the thief a way to impersonate every coat owner). And once you've collected your coat, that same stub doesn't work again — someone finding a used stub in the trash gets nothing.

**Why we needed it here (D8/D9):** if the raw token were stored in `users.reset_token_hash` verbatim, a database leak (backup exposed, SQL injection, careless log line) would hand an attacker a working "reset anyone's password" link for every account with a pending reset — turning one breach into full account takeover, immediately, for every affected user. Hashing means a leak of the database only leaks hashes, which are useless without the original random value (this is the exact same reasoning Phase 1 already applied to refresh tokens).

**How it works:**
1. Generate 32 bytes from `SecureRandom`, Base64URL-encode them — the same recipe [`ViewerSessionService.generateSessionToken`](../../backend/src/main/java/com/secureleaf/viewer/service/ViewerSessionService.java#L249) already uses for a viewer session token (Phase 5). Reusing a proven "how do we mint an unguessable token" recipe rather than inventing a second one.
2. Store only `jwtService.hashToken(rawToken)` (SHA-256 hex — the same hashing helper Phase 1 built for refresh tokens) plus an expiry 30 minutes out.
3. Email the raw token, embedded in a link: `{frontendBaseUrl}/reset-password?token=...`. This is the *only* place the raw value ever exists outside memory.
4. `resetPassword` hashes whatever token it's given and looks up a user by that hash. On success, it clears both `reset_token_hash` and `reset_token_expires_at` — so the *next* lookup by that same hash finds nothing, making the token single-use.

**In our code:** [`PasswordResetService.java:96-108`](../../backend/src/main/java/com/secureleaf/auth/service/PasswordResetService.java#L96)
```java
User user = userRepository.findByResetTokenHash(jwtService.hashToken(rawToken))
        .filter(u -> u.getResetTokenExpiresAt() != null && u.getResetTokenExpiresAt().isAfter(Instant.now(clock)))
        .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_TOKEN, "This reset link is invalid or has expired."));

user.setPasswordHash(passwordEncoder.encode(newPassword));
user.setResetTokenHash(null);
user.setResetTokenExpiresAt(null);
userRepository.save(user);
```
Every failure mode here — token doesn't exist, token expired, token already used (which, after the clear above, looks identical to "never existed") — collapses into the **same** `INVALID_TOKEN` error. That's a deliberate choice, not laziness: distinguishing "expired" from "already used" from "never existed" in the error message would tell an attacker holding a guessed/stolen token *which* of those three is true, information they have no legitimate use for. (A genuinely different kind of failure — the new password itself being too short — gets its own distinct error, `INVALID_INPUT`, because that one *is* something the legitimate user needs to know how to fix.)

Expiry is checked against an **injectable `Clock`** (`user.getResetTokenExpiresAt().isAfter(Instant.now(clock))`), not a bare `Instant.now()` — the same pattern `TileUrlSigner` established in Phase 5 specifically so this exact check can be unit-tested with a fixed "now" instead of a real 30-minute sleep. `PasswordResetServiceTest` does exactly that: constructs the service directly with a `Clock.fixed(...)` one second before expiry (succeeds) and one second after (fails), with no Spring context and no database.

**What breaks without it:** storing the raw token turns a database leak into an instant, mass account-takeover incident. Skipping the "clear on success" step turns a password-reset link into a *reusable* password-reset link — anyone who ever saw it (a shared inbox, a browser's autofill history, a screenshot) could reset the password again, later, even after the legitimate user already used it once.

---

### 3.6 Revoking sessions on credential change

**What it is:** the moment a password is successfully reset, every refresh token issued to that account under the *old* password is revoked — so a session that started before the reset (say, on a device an attacker still has open) stops working.

**The analogy:** changing the locks on your house doesn't just stop someone from making a new copy of the old key — a proper locksmith also physically changes the mechanism so a copy already made stops working too.

**Why we needed it here (D9):** the single most common *reason* someone resets a password is "I think someone else might have it" — meaning there might already be an active, stolen session using it. If that session's refresh token still works after the reset, the reset didn't remove the attacker's access; it just added a lock they don't need, because they're already inside.

**How it works:** [`PasswordResetService.java:110`](../../backend/src/main/java/com/secureleaf/auth/service/PasswordResetService.java#L110)
```java
refreshTokenRepository.revokeAllForUser(user.getId(), Instant.now(clock));
```
`revokeAllForUser` already existed — Phase 1 built it for refresh-token-reuse detection (a stolen-and-replayed token triggers the same "revoke everything" response). This phase is its second caller, not a new mechanism: one more situation ("credential changed") recognized as needing the same "kill every existing session" response that "token reuse detected" already gets. The access token an attacker's session might still be holding (up to 15 minutes old) still technically works until it expires on its own — revoking is scoped to *refresh* tokens, the thing that lets a session renew itself indefinitely; the honest gap this leaves is recorded in §10.

**What breaks without it:** a password reset that's cosmetic — it changes what a *new* login needs, but does nothing about a session that's already running.

---

### 3.7 Rate limiting with Redis counters

**What it is:** capping how many times something can happen in a time window, using Redis's `INCR` (atomic increment, returns the new value) plus `EXPIRE` (set a time-to-live on a key) as the two primitives.

**The analogy:** a bouncer with a tally counter that resets itself every hour. Each request clicks the counter; past a threshold, new arrivals are turned away — quietly, without the counter itself resetting early just because someone was turned away.

**Why we needed it here (D8):** without a limit, `requestPasswordReset` is a free "send an email to anyone" button — an attacker could hammer a stranger's inbox with reset emails purely to annoy them, or worse, as cover/noise around some other attack. The limit is **per email address**, not per caller/IP — the resource being protected is *the victim's inbox*, not the requester's fair use of the API, so it has to key on the thing that's actually scarce.

**How it works:** [`PasswordResetService.java:126-136`](../../backend/src/main/java/com/secureleaf/auth/service/PasswordResetService.java#L126)
```java
private boolean withinRateLimit(String normalizedEmail) {
    String key = RATE_LIMIT_KEY_PREFIX + normalizedEmail;
    Long count = redisTemplate.opsForValue().increment(key);
    if (count != null && count == 1L) {
        redisTemplate.expire(key, RATE_LIMIT_WINDOW);
    }
    return count != null && count <= MAX_REQUESTS_PER_HOUR;
}
```
`INCR` on a key that doesn't exist yet creates it at `1` and returns `1` — so "was this the very first request in a fresh window?" is answered by the return value itself, with no separate existence check (no check-then-act race between "does the key exist" and "create it"). Only that first call sets the one-hour `EXPIRE`; every later call in the same window just increments the existing counter without touching its TTL, so the window is anchored to the *first* request, not extended by later ones. Once the count exceeds the cap, `requestPasswordReset` still returns `true` (§3.4) — it just skips publishing the email event, so the caller genuinely cannot tell "rate-limited" apart from "sent."

**What breaks without it:** a password-reset button that doubles as a free tool for harassing an arbitrary stranger's inbox, with no cost to the person clicking it.

---

## 4. Best practices applied

| Practice | What we did | Why it matters | Where |
|---|---|---|---|
| Pessimistic lock before a read-then-write aggregate | `FOR UPDATE` on the product row before the review write and the aggregate recompute | Closes the lost-update anomaly at its root instead of detecting it after the fact | `ProductRepository.java` (`findByIdForUpdate`) |
| Recompute, don't increment, a derived aggregate | Aggregate is `ROUND(AVG(...))`/`COUNT(*)` from source rows, every time | Correct for edits and deletes, not just new-row inserts | `ProductRepository.java` (`recalculateReviewAggregate`) |
| Data minimisation in the API type, not the resolver | `ReviewerSummary { displayName }`, no `id`, no path to `email` | The leak is structurally impossible, not just currently avoided | `schema.graphqls` |
| Enumeration-safe response shape | `requestPasswordReset` always returns `true`; frontend shows one message either way | A boolean (or a differing UI) is itself the leak if it varies with account existence | `PasswordResetService.java`, `ForgotPasswordPage.tsx` |
| Hash, don't store, a bearer secret | SHA-256 of the raw reset token, same helper as refresh tokens | A DB leak yields unusable hashes, not working "log in as anyone" links | `PasswordResetService.java`, `JwtService.hashToken` |
| Single-use by deletion | Token fields cleared the moment a reset succeeds | A used link can't be replayed later by anyone who saw it once | `PasswordResetService.java` |
| Revoke sessions on credential change | `revokeAllForUser` called from `resetPassword`, reusing Phase 1's reuse-detection path | A reset actually removes a stolen session's access, not just future logins' | `PasswordResetService.java` |
| Injectable `Clock` for time-based logic | Expiry compared against `Instant.now(clock)`, not a bare `Instant.now()` | Unit-testable expiry with a fixed "now," no real sleeping in tests | `PasswordResetService.java`, `PasswordResetServiceTest.java` |
| One generic error for every token failure | Missing/expired/used token all report `INVALID_TOKEN` | Doesn't tell an attacker holding a bad token *which* of three things is wrong | `PasswordResetService.java` |
| AFTER_COMMIT + async side effects | Reset emails send only after the token's transaction commits, off the request thread | No email for a token that got rolled back; SMTP latency never blocks the response | `PasswordResetMailer.java` |
| Keyboard-accessible custom widget | Star picker is an ARIA `radiogroup`/`radio` set with arrow-key roving tabindex | A mouse-only rating control excludes keyboard users entirely | `StarRatingInput.tsx` |

---

## 5. What does what — file map

| File | Responsibility |
|---|---|
| `ReviewService.java` | Submit (upsert)/delete a review, the three-step locked aggregate recompute, paginated reads, rating breakdown |
| `ReviewResolver.java` | GraphQL mutations/queries for reviews — public reads, authenticated writes |
| `ReviewRepository.java` | Buyer/product lookup, paginated fetch (eager `buyer`), the (rating, count) breakdown query |
| `ProductRepository.java` | `findByIdForUpdate` (the lock) and `recalculateReviewAggregate` (the recompute), added this phase |
| `ReviewMapper.java` | Entity → DTO, inside the transaction (`Review.buyer` is LAZY) |
| `ReviewerSummaryDto`/`ReviewDto`/`ReviewPageDto`/`RatingCountDto` | The privacy-safe API shape |
| `ProductSearchRepositoryImpl.java` | `minRating` added to the marketplace filter's native-SQL `WHERE` |
| `PasswordResetService.java` | `requestPasswordReset`/`resetPassword` — rate limiting, token mint/verify, session revocation |
| `PasswordResetMailer.java` | AFTER_COMMIT/async listener that actually sends the email |
| `PasswordResetMailRequestedEvent.java` | Carries the fully-composed subject/body to the listener |
| `AppProperties.java` | `app.frontend-base-url` — where the emailed reset link points |
| `V6__reset_token_index.sql` | Partial index on `users.reset_token_hash` (the lookup `resetPassword` does on every attempt) |
| `useProductReviews.ts` | Frontend: bundles the paginated reviews/breakdown/myReview queries and the two mutations |
| `RatingHistogram.tsx` / `ReviewList.tsx` / `ReviewForm.tsx` / `StarRatingInput.tsx` / `StarRating.tsx` | Product detail page's review section |
| `ForgotPasswordPage.tsx` / `ResetPasswordPage.tsx` | The two new auth screens |

**Request trace — a buyer edits their review, and the histogram updates:**
1. `ReviewForm` (already showing the buyer's existing review) → `submitReview` mutation with the new rating/text →
2. `ReviewResolver.submitReview` → `ReviewService.submitReview` checks entitlement + not-the-creator →
3. locks the product row, finds the existing `Review` by (buyer, product), updates it in place →
4. `recalculateReviewAggregate` re-derives `average_rating`/`review_count` from the `reviews` table, still holding the lock →
5. the mutation returns the updated `Review`; the frontend's `refetchAll` re-runs `productReviews`, `ratingBreakdown` and `myReview` →
6. `RatingHistogram` re-renders with the new average and bar heights.

**Request trace — password reset, end to end:**
1. `ForgotPasswordPage` → `requestPasswordReset(email)` →
2. `PasswordResetService` checks the email exists, checks the Redis rate limit, mints a token, hashes and stores it, publishes `PasswordResetMailRequestedEvent` →
3. AFTER_COMMIT, async: `PasswordResetMailer` sends the email with the raw token in a link →
4. buyer clicks the link → `ResetPasswordPage` reads `?token=` from the URL →
5. `resetPassword(token, newPassword)` → hash lookup, expiry check, BCrypt the new password, clear the token fields, `revokeAllForUser` →
6. frontend redirects to `/login` with a one-time confirmation banner.

---

## 6. Design decisions and trade-offs

### Decision: pessimistic row lock + full recompute, not an incremental formula or a nightly job
- **Alternatives considered:** incremental running average; nightly batch recompute; `SERIALIZABLE` isolation.
- **Why we chose this:** correct under concurrency *and* correct for edits/deletes, with a cost (one row lock, briefly held) that's negligible at this app's write volume. See §3.1's table for why each alternative loses.
- **What we gave up:** every review write for the *same* product now briefly serializes behind whichever write got there first — invisible at real-world review volume, but a genuine bottleneck if one product ever received thousands of reviews per second (it won't, at this app's scale).
- **When we would revisit:** if a single product's review-write rate ever became a measured bottleneck — the fix would likely be an async recompute queue, accepting brief staleness in exchange for no lock contention.

### Decision: `ReviewerSummary` with no `id`, narrower even than `CreatorSummary`
- **Alternatives considered:** reuse `CreatorSummary` (which has `id` + `displayName`); keep `buyer: User!` and just tell the frontend not to query `email`.
- **Why we chose this:** the schema is the actual boundary — a resolver-level "please don't ask for this" is advice, not enforcement. See §3.3.
- **What we gave up:** nothing real — no current feature needs a reviewer's id.
- **When we would revisit:** if a future feature legitimately needed to link to a reviewer's public profile, `id` could be added back deliberately, as its own reviewed decision — not by accident.

### Decision: enumeration safety over a slightly better error message
- **Alternatives considered:** return `false` for an unknown email (clearer UX, tells the user immediately); return a specific error for a Google-only account.
- **Why we chose this:** the entire point of AUTH-07 is that the API's shape must not reveal account existence. A clearer error message for one case is a worse security posture for everyone. See §3.4.
- **What we gave up:** a user who mistyped their email gets the same "check your inbox" message as one who typed it correctly — mildly confusing, but the alternative is the enumeration bug.
- **When we would revisit:** never, for this endpoint specifically — this is the correct trade-off for a public, unauthenticated recovery flow, not a temporary compromise.

### Decision: reuse `revokeAllForUser` rather than build a separate "reset flow" revocation
- **Alternatives considered:** a dedicated revocation method scoped to password resets, with its own audit trail.
- **Why we chose this:** "something changed that means old sessions must die" is one concept — reuse detection (Phase 1) and password reset (this phase) are just two different *triggers* for the same response. One method, two call sites, is easier to reason about than two subtly-different implementations of "kill every session."
- **What we gave up:** the two triggers share one log line shape; if they ever needed different follow-up behaviour (e.g. notifying the user only for one of them), they'd need to diverge.
- **When we would revisit:** if a future requirement needed the two triggers to behave differently (e.g. a security-alert email specifically for reuse detection, not for a self-initiated reset).

---

## 7. Interview questions

### Beginner
**Q: Why can't a creator review their own product?**
A: It's an obvious conflict of interest for a marketplace's ratings — self-reviews would make every product look artificially good. The check happens before the entitlement check even runs: if the caller is the product's creator, it's an `ACCESS_DENIED`, full stop, regardless of whether they happen to also hold an entitlement.

**Q: What does `requestPasswordReset` return for an email that has no account?**
A: `true` — the exact same thing it returns for an email that does. That's deliberate: returning something different would let anyone probe which emails have accounts, which is its own privacy problem (user enumeration).

### Intermediate
**Q: Walk me through what happens if two buyers submit a review for the same product at the same instant.**
A: Both `submitReview` calls try to lock the same product row with `SELECT ... FOR UPDATE`. Whichever gets there first holds the lock for the rest of its transaction; the second one blocks until the first commits. Only then does the second one proceed — write its review, then recompute the aggregate from the `reviews` table, which by now includes *both* rows. So the final `review_count` and `average_rating` are exactly right, and neither buyer's review is lost, because the aggregate recompute never runs concurrently with another write to the same product.

**Q: Why store a hash of the reset token instead of the token itself?**
A: Same reasoning as refresh tokens in Phase 1 — if the database ever leaked (a backup, a misconfigured log, an injection bug), a table of raw reset tokens would hand an attacker a working "become anyone with a pending reset" link immediately. A table of SHA-256 hashes is useless without the original random value, which only ever existed in the email that was sent and the requester's memory of clicking it.

### Advanced / follow-up probes
**Q: The design doc calls for a pessimistic lock on the product row before recomputing the aggregate. Why not just let the `UPDATE`'s own row-level lock handle that?**
A: In Postgres, an `UPDATE` does take a row lock on the row it's writing — but not until it *starts* writing. If two transactions each insert their own review first and only then run the aggregate `UPDATE`, Postgres's `READ COMMITTED` isolation actually re-checks the `UPDATE`'s subqueries after acquiring the lock (its "EvalPlanQual" behaviour), so in this *exact* shape it would likely still come out correct by luck of how Postgres implements `UPDATE`. But that correctness is an implementation detail of how Postgres specifically handles blocked `UPDATE`s — it's not something the code visibly guarantees, and it wouldn't hold at all if the aggregate were instead computed in Java and written back through the entity (the shape that risks a real lost update, see §3.1). Taking the lock explicitly, as the very first step, makes the serialization the code's own guarantee rather than an accident of how one database happens to implement one statement.

**Q: You said every reset-token failure returns the same `INVALID_TOKEN` error. Doesn't that make debugging harder for you, the developer?**
A: It makes debugging harder for an *attacker*, which is the point — the three failure reasons (token never existed, token expired, token already used) tell them nothing useful to try next. For legitimate debugging, the server-side log line still records the specific reason (`log.info` at each branch internally, before the generic exception is thrown), so an engineer looking at logs has full detail; only the *client-facing* error is deliberately vague. That split — detailed internally, generic externally — is the general pattern for any security-sensitive failure, not just this one.

### "Tell me about a bug you fixed"
**Q: Tell me about a mistake you almost shipped in this phase.**
A: The original schema had `type Review { buyer: User! }`, sitting there unused since Phase 0 with no resolver behind it. My first instinct writing `ReviewMapper` was to just map the existing `Review.buyer` entity straight through, since the type already existed and matched the entity's field name — the path of least resistance. Stopping to ask "does *this specific field*, `email`, belong on a review anyone can see" is what caught it before any resolver ever executed with that type — not a code review catching it after the fact, but noticing during design that reusing `User` here was reusing a type that happened to be convenient, not one that was actually *right* for this relationship. The fix was a new, narrower type (`ReviewerSummary`) instead of trying to remember, everywhere, "don't select `email` on this particular `User`." Lesson: when a type you're about to reuse carries more fields than your current feature needs, that's the moment to ask whether it's the right type at all — not after something ships with it.

---

## 8. Gotchas and bugs we hit

| Symptom | Root cause | Fix | Lesson |
|---|---|---|---|
| A test that read `refreshTokenRepository.findByTokenHashForUpdate(...)` outside of any `@Transactional` test method threw `InvalidDataAccessApiUsage: Query requires transaction be in progress` | That repository method carries `@Lock(PESSIMISTIC_WRITE)` — Spring Data JPA requires an active transaction for any locked query, since a lock held by no transaction is meaningless | Asserted the revoked state via a plain `jdbcTemplate` read instead, which needs no transaction | A repository method's `@Lock` annotation is a hard requirement on its *caller's* transactional context, not just a hint — reads that don't need the lock shouldn't go through the locked method at all |
| `PasswordResetMailer`/`NotificationDispatcher` both send real `SimpleMailMessage`s through `JavaMailSender`, but there's no SMTP server in CI | `spring-boot-starter-mail` auto-configures a real `JavaMailSenderImpl` pointed at `application.yml`'s dev Mailpit settings, which don't exist in the test environment | Added `RecordingMailSender`, a `@Primary` test-only `JavaMailSender` that just records messages in memory — the same "swap the real thing for an in-memory fake behind `@Primary`" trick this codebase already uses for storage (`InMemoryStorageService`) and Redis pub/sub (`RecordingNotificationPublisher`) | A test double at the *Spring bean* boundary (not a mock injected per-test) means every test in the suite gets the safe behaviour automatically, with no risk of one forgotten test actually dialing out to a real mail server |
| An IT test asserting `mailSender.sent()` immediately after calling the `requestPasswordReset` mutation intermittently found nothing sent | `PasswordResetMailer` is `@Async` + `@TransactionalEventListener(AFTER_COMMIT)` — the email genuinely hasn't been sent yet at the instant the HTTP response comes back; it happens shortly after, on a different thread | Used `Awaitility.await().untilAsserted(...)` to poll for the expected email count, the same pattern `NotificationIT` already established in Phase 4 for the identical async-side-effect shape | An assertion checked immediately after a call that triggers an `@Async` side effect is racing that side effect, not verifying it — this is a shape to recognize on sight, not rediscover by a flaky test |

---

## 9. New vocabulary

| Term | One-line meaning |
|---|---|
| Denormalized aggregate | A derivable value (e.g. `AVG(rating)`) stored again on another table for read speed, at the cost of having to keep it in sync |
| Lost-update anomaly | Two concurrent read-modify-writes where the second silently overwrites the effect of the first |
| Upsert | Insert-if-absent, update-if-present, as one operation |
| Data minimisation | Returning only the fields a feature actually needs, never "whatever the type happens to also carry" |
| User enumeration | Learning which accounts exist from how a system's responses differ, even without an explicit yes/no |
| Single-use token | A credential that's deleted/invalidated the instant it's successfully used once |
| Session revocation | Invalidating already-issued credentials (here, refresh tokens) so they stop working immediately, not just on their next natural expiry |
| `INCR`/`EXPIRE` rate limiting | Redis's atomic counter-with-a-deadline pattern for capping how often something may happen in a window |
| ARIA `radiogroup`/`radio` | The accessibility roles that make a custom multi-option picker (like a star rating) behave like a native radio button set for keyboard/screen-reader users |
| Roving tabindex | Only one item in a custom widget is ever reachable by Tab; arrow keys move focus (and, here, selection) among the rest |

---

## 10. If I had to defend this in a code review

- **Strongest point:** the aggregate-correctness design (§3.1) is proven, not assumed — `ReviewIT` actually races two threads with a `CountDownLatch` and checks the exact final numbers, the same standard Phase 4's commerce concurrency tests set.
- **Also strong:** the `ReviewerSummary` privacy fix is caught and closed at the *type* level, with a test that asserts the leak is a schema-validation error, not just "the current resolver happens not to expose it."
- **Weakest point, and I'd fix it first:** `resetPassword` revokes refresh tokens but not the currently-live 15-minute access token an attacker's stolen session might still hold — for up to 15 minutes after a "successful" reset, that old session can still make authenticated requests. Fully closing that gap needs a server-side access-token blocklist (checked on every request) or shortening the access-token lifetime, both of which add real cost (a Redis check per request, or more frequent refreshes) that wasn't in scope for this phase. It's an accepted, time-bounded gap, not an oversight — but it's the first thing I'd bring up unprompted.
- **Also worth naming honestly:** the enumeration-safety story has one remaining side channel — response *timing*. A known-LOCAL email does real work (hash a token, write a row, publish an event) that an unknown email doesn't; a sufficiently patient, statistically-minded attacker measuring response latency at scale could in principle still infer something. Closing that fully (e.g. a dummy BCrypt-shaped delay for the unknown-email path) is the kind of hardening that belongs in the Phase 09 hardening pass, not invented ad hoc here.
