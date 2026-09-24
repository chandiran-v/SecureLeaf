# Phase 07 — Reviews & ratings + password reset

> Spec for an unattended run. Decisions are numbered (D1…) and referenced from code comments.
> Requirements: **REV-01..04**, **MARKET-03** (the rating filter), **AUTH-07**.
> Depends on: Phase 06.

## Context

- `reviews` exists with `UNIQUE (buyer_id, product_id)` and a rating CHECK of 1–5.
- `products.average_rating` / `review_count` exist but nothing writes them.
- The schema declares `submitReview` and `productReviews`, with **no resolver**.
- `type Review { buyer: User! }` would expose **the reviewer's email**. That is a privacy bug to fix here.
- `users.reset_token_hash` / `reset_token_expires_at` exist, unused.
- Mail goes out through Spring Mail (Mailpit in dev).

## Decisions — Reviews

- **D1 — Who can review (REV-01, 03).** Only a user with an ACTIVE entitlement for the product. The creator can never review their own product. Otherwise throw `NOT_ENTITLED` / `ACCESS_DENIED`.
- **D2 — One review per buyer per product, editable.** `submitReview` is an **upsert**: a second call edits the existing review and bumps `updated_at`. Also add `deleteMyReview(productId): Boolean!`. The unique constraint stays as the final guard; map a violation to a clean error, as Phase 4 does for idempotency.
- **D3 — Validation.** Rating is an integer 1–5. `reviewText` is optional, trimmed, and at most 2,000 characters. Plain text only: the frontend renders it as text, never as HTML.
- **D4 — Keeping the aggregate correct under concurrency (REV-04).** In the same transaction as the insert, update or delete, run:
  1. `SELECT … FROM products WHERE id = ? FOR UPDATE`, to serialise writers on the product row.
  2. The review write.
  3. `UPDATE products SET average_rating = (SELECT ROUND(AVG(rating)::numeric, 2) …), review_count = (SELECT COUNT(*) …)`. The average is `NULL` when there are no reviews.

  **Why the lock:** under READ COMMITTED, two concurrent reviews could each compute the aggregate from a snapshot that doesn't include the other one. The row lock serialises them. The learning note explains this and compares it with the alternatives: an incremental formula (drifts on edit and delete), a nightly recompute (stale), and SERIALIZABLE (retry storms).

  Mind the `@Version` column on `Product`: the native update must not fight optimistic locking. Either bump `version` in the same statement or go through the entity. Pick one and explain it.
- **D5 — Privacy.** Replace `Review.buyer: User!` with `reviewer: ReviewerSummary!`, where `ReviewerSummary { displayName: String! }`. Never expose email. Add a test that the query cannot select an email.
- **D6 — Queries.**
  - `productReviews(productId, page, size)` returns `ReviewPage { content, totalElements, totalPages, pageNumber }`, newest first. Size is capped at 50.
  - `myReview(productId): Review`.
  - Also add `ratingBreakdown(productId): [RatingCount!]!` (count per star) for the histogram.
- **D7 — Marketplace (MARKET-03).** `ProductFilterInput.minRating: Float` filters `average_rating >= minRating`. `sortBy: rating` already exists; make sure products with no reviews sort last.

## Decisions — Password reset (AUTH-07)

- **D8 — `requestPasswordReset(email): Boolean!` always returns `true`,** whether or not the account exists. That prevents user enumeration.
  - For a LOCAL account: generate 32 random bytes. Store only the SHA-256 hash plus an expiry 30 minutes out. Email a link to `{frontendBaseUrl}/reset-password?token=…`.
  - For a GOOGLE-only account: email "you sign in with Google" instead.
  - Rate limit: at most 3 requests per email per hour (Redis `INCR` + `EXPIRE`). Over the limit, silently skip sending but still return `true`.
- **D9 — `resetPassword(token, newPassword): Boolean!`.**
  - Hash the token and look it up. It must exist and not be expired. It is single-use: clear it on success.
  - Validate the new password with the same rules as registration. BCrypt it.
  - **Revoke all the user's refresh tokens**, so a stolen session dies with the old password.
  - A generic error covers every failure case (`INVALID_TOKEN`).
- **D10 — Config.** `app.frontend-base-url: ${FRONTEND_BASE_URL:http://localhost:5173}`. The email is sent after commit, through the existing async mail path.

## Frontend
- **Product detail:**
  - Rating summary: average, count, and a star histogram from `ratingBreakdown`.
  - Paginated review list.
  - For entitled buyers, a review form: star input (keyboard accessible, `role="radiogroup"`) and a textarea, prefilled from `myReview`, with Edit and Delete.
  - Cards already show `averageRating`; show "No reviews yet" when it's null.
- **Marketplace filters:** add a "4★ & up" / "3★ & up" rating filter.
- **Password reset:**
  - A "Forgot password?" link on `LoginPage`.
  - `/forgot-password` (email form; always shows "If an account exists, we've sent a link").
  - `/reset-password?token=` (new password + confirm; on success, redirect to login with a toast).

## Acceptance criteria
1. A non-entitled user and the creator are both rejected by `submitReview`.
2. A second `submitReview` updates the existing review; there is still one row, and the aggregate reflects the edit. Delete recomputes the aggregate, back to `NULL` when none are left.
3. **Concurrency test:** two different buyers submit reviews at the same time (two threads plus a `CountDownLatch`). Afterwards `review_count = 2` and the average is exact.
4. `productReviews` paginates, and a query asking for an email field fails schema validation.
5. `minRating` filters correctly, and unrated products sort last under `sortBy: rating`.
6. `requestPasswordReset` returns `true` for an unknown email and sends nothing, sends exactly one email for a known email (capture it with a test mail sender), and stops sending after 3 requests while still returning `true`.
7. `resetPassword`:
   - succeeds once and fails when the token is reused
   - fails after expiry (using a `Clock`)
   - after success, the old password fails and the new one works
   - existing refresh tokens no longer work
8. Frontend tests: the star input works with the keyboard, the review form prefills and edits, the forgot-password screen shows the generic message, and the reset flow validates matching passwords.

## Out of scope
- Review moderation or reporting (post-MVP).
- Helpful votes.
- Creator replies.
- Verified-purchase badges beyond the entitlement check.
- Email-change flows.

## Learning note
Create `docs/learning-notes/phase-07-reviews-password-reset.md`. Headline topics:
- denormalised aggregates, and keeping them correct (row lock vs alternatives; lost-update anomaly)
- upserts
- the privacy leak we caught in the GraphQL type (data minimisation)
- user enumeration
- reset-token hashing and single use
- revoking sessions on credential change
- rate limiting with Redis counters
