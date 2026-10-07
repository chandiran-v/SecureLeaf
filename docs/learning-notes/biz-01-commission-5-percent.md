# Biz 1 — Platform commission cut from 10% to 5%

> **Status:** Done
> **Built:** 2026-10-03
> **Requirement IDs covered:** PAY-10 (creator revenue = price − commission), PAY-07 (high-level)
> **Commits:** _(uncommitted at time of writing)_

---

## 1. What we built, in plain English

SecureLeaf used to keep 10% of every sale and give the creator 90%. Now it keeps **5%** and the
creator gets **95%**. On a ₹499 document the creator now gets ₹474.05 instead of ₹449.10.

The interesting part is how *little* had to change, and what did **not** change. The rate lives in
one config value on the server. Every past sale already stores its own fee, written down when the
sale happened. So sales made before today **stay at 10%**: their history is not rewritten. Only
orders created after the new config is deployed are charged 5%.

**Before:** `commerce.platform-fee-bps: 1000` (10%), and "10%" / "90%" typed by hand into four
frontend files (Terms, About, the dashboard earnings card and the Payouts balance card).
**After:** `platform-fee-bps: 500` (5%), and the frontend reads one constant,
`PLATFORM_FEE_PERCENT`, in all four places.

---

## 2. Why it matters

A commission rate is a **business rule that will change again**: a promo, a creator tier, a
competitor's move. This change was a chance to check that the Phase 4 design really works. It did:
the backend needed **one line of config**, and **no database migration**.

What would break if the system had been built the naive way:
- **History rewrite.** If earnings were computed as `price × current rate`, every creator's lifetime
  earnings, monthly statements and already-paid payouts would suddenly be recalculated at 5%. The
  numbers would no longer match the money that actually moved.
- **Display drift.** The four hardcoded "10%" strings would keep telling creators 10% while the
  server charged 5%. Nothing would be wrong in the database, but the **Terms of Service would be
  wrong**. For a marketplace, the Terms are a contract.

---

## 3. New concepts introduced

### 3.1 Config-driven business rules

**What it is:** a business number (a rate, a limit, a window) lives in configuration, not in code,
so changing it doesn't mean editing logic.

**The analogy:** a restaurant prints its prices on a menu board, not into the recipe. You change
the board, not how the dish is cooked.

**Why we needed it here:** the commission changed. The code that does the fee maths didn't need to.

**How it works:** Spring binds `commerce.platform-fee-bps` from `application.yml` into a typed
record (`@ConfigurationProperties`). Business code asks the record for the fee.

**In our code:** `backend/src/main/resources/application.yml:108`
```yaml
  platform-fee-bps: 500          # 5% platform commission, in basis points (PAY-10, D7). ...
```
`backend/src/main/java/com/secureleaf/commerce/CommerceProperties.java:22`
```java
public long platformFeeFor(long pricePaise) {
    return (pricePaise * platformFeeBps + 5_000) / 10_000;
}
```

**What breaks without it:** a rate change becomes a code change, with a rebuild, a code review and a
risk of missing one of several places where the number is written.

### 3.2 Snapshotting (why no migration was needed)

**What it is:** when a value matters for history, copy it onto the record at the moment it happens,
instead of looking it up later.

**The analogy:** a till receipt prints the price you paid. If the shop raises the price tomorrow,
your receipt doesn't change.

**Why it mattered here:** it is the reason the change is safe. Each order item stores
`platform_fee_paise` and `creator_earnings_paise` when the order is created
(`OrderService.java:159-171`):
```java
long fee = commerceProperties.platformFeeFor(price);
...
item.setPlatformFeePaise(fee);              // D7 — commission snapshot
item.setCreatorEarningsPaise(price - fee);  // derived by subtraction: always sums exactly
```
Everything downstream (creator earnings, balance, statements, payouts, admin stats) **sums those
stored columns** and never multiplies by the rate again. While making this change I checked that
claim by searching `creator/`, `admin/` and `loadtest/` for any rate-based maths. There was none.

**What breaks without it:** see §2, "History rewrite".

### 3.3 Basis points, revisited

**What it is:** 1 basis point (bps) = 0.01%. 5% = 500 bps. Rates stay integers, so the fee maths
never uses floating point.

**Worked example (the new test numbers):** ₹499 = 49,900 paise.
`(49,900 × 500 + 5,000) / 10,000 = 24,955,000 / 10,000 = 2,495` (integer division).
So the fee is ₹24.95 and the creator gets 49,900 − 2,495 = 47,405 paise = ₹474.05.

### 3.4 Single source of truth — and its honest limit

**What it is:** each fact should be written in exactly one place; everything else refers to it.

**In our code:** `frontend/src/content/legal/types.ts:35`
```ts
export const PLATFORM_FEE_PERCENT = 5;
```
It is used by `terms.ts:34`, `about.ts:16`, `BalanceCard.tsx:12` and `CreatorDashboardPage.tsx:338`.
The Terms derive "the remaining 95%" as `100 - PLATFORM_FEE_PERCENT`, so the two numbers can never
disagree. It sits next to `REFUND_WINDOW_DAYS`, which already followed this pattern.

**The honest limit:** there are still **two** sources: the backend bps and the frontend percent.
They are linked only by comments pointing at each other. See §10.

---

## 4. Best practices applied

| Practice | What we did | Why it matters | Where |
|---|---|---|---|
| Change config, not code | One YAML value | Smallest, safest change for a business rule | `application.yml:108` |
| Snapshot money at write time | Already in place (Phase 4, D7); verified, not changed | Past sales keep 10%; no history rewrite | `OrderService.java:170` |
| No migration for a non-schema change | Left `V4`'s CHECKs alone; they enforce `fee + earnings = price`, not the rate | Applied migrations are never edited (AI_RULES Rule 6) | `V4__commerce_integrity.sql:30` |
| De-duplicate magic numbers | Four "10%" strings → one constant | Display can't drift from itself | `content/legal/types.ts:35` |
| Known-answer tests | Tests assert literal `2_495` / `47_405`, not `platformFeeFor(...)` | A test that calls the code under test to get its expectation can't catch a formula bug | `CommerceIT.java:76` |
| Keep history docs historical | Phase specs/notes still say 10% (true *then*); current-state docs say 5% | Docs record decisions in context | `docs/requirements.md` PAY-10 |

---

## 5. What does what — file map

| File | Responsibility |
|---|---|
| `backend/src/main/resources/application.yml` | **The** rate that splits real money (`500` bps) |
| `backend/.../commerce/CommerceProperties.java` | Binds the rate; `platformFeeFor` does the integer maths |
| `backend/.../commerce/service/OrderService.java` | Snapshots fee + earnings onto the order item at creation |
| `backend/.../commerce/repository/OrderItemRepository.java` | Sums stored snapshots for earnings (never recomputes) |
| `frontend/src/content/legal/types.ts` | `PLATFORM_FEE_PERCENT` — display copy of the rate |
| `frontend/src/content/legal/terms.ts`, `about.ts` | Legal/marketing text built from the constant |
| `frontend/src/components/payouts/BalanceCard.tsx`, `pages/creator/CreatorDashboardPage.tsx` | Creator-facing hints |
| `CommerceIT`, `RazorpayFlowIT`, `LegalPages.test.tsx` | Updated known answers (5% / 95%) |

**Request trace — a buyer buys a ₹499 document after the change:**
1. `initiateOrder` mutation → `OrderService.createOrder` →
2. `CommerceProperties.platformFeeFor(49_900)` = `2_495` (reads `500` bps) →
3. `OrderItem` saved with `platform_fee_paise = 2495`, `creator_earnings_paise = 47405`; the DB CHECK confirms they add up to `price_paise` →
4. Later, the creator's dashboard / balance / statement **sum** those columns, so a mix of old 10% rows and new 5% rows adds up correctly.

---

## 6. Design decisions and trade-offs

### Decision: apply 5% to new orders only (no backfill)
- **Alternatives considered:** a migration that rewrites old `order_items` to 5%.
- **Why we chose this:** money that was already split (and maybe paid out) at 10% must stay at 10%.
  Rewriting it would make balances disagree with real transfers. The snapshot design gives us this
  for free.
- **What we gave up:** a creator with old sales sees a *lifetime* fee that is more than 5% of their
  lifetime gross, while the label says "(5%)".
- **When we would revisit:** if that confuses creators, label it "platform fee" without a percent,
  or show the effective rate.

### Decision: frontend constant instead of serving the rate over GraphQL
- **Alternatives considered:** add `platformFeeBps` to the GraphQL schema and have the frontend
  query it.
- **Why we chose this:** the Terms page is static content and is rendered without a server call. A
  constant matches the existing `REFUND_WINDOW_DAYS` pattern and keeps the change small.
- **What we gave up:** two places to update (backend YAML + frontend constant).
- **When we would revisit:** as soon as the rate varies (per creator tier, promos), or if it is set
  through an env var in production. Then the server must be the only source of truth.

### Decision: keep platform paying gateway fees
- Unchanged from Phase 09B, but the economics tightened. Razorpay takes ~2% + 18% GST ≈ **2.36%**. At
  10% the platform kept ~7.6% net per sale. At 5% it keeps ~**2.64%**. On ₹499: 2,495 − 1,178 =
  **1,317 paise** net (was 3,812). A refunded sale still loses the whole gateway fee. Still positive,
  but there is much less margin for refunds.

---

## 7. Interview questions

### Beginner
**Q: How do you change the commission rate in SecureLeaf?**
A: One config value, `commerce.platform-fee-bps` in `application.yml`. It's in basis points, so 5%
is 500. The fee code reads it through a typed `@ConfigurationProperties` record, so no logic changes.
There's a matching display constant on the frontend for the Terms and dashboard text.

**Q: Why basis points instead of `0.05`?**
A: So everything stays integer. Money in paise times an integer rate, divided by 10,000, rounded
half-up by adding 5,000 first. No floating point, so no paisa appears or vanishes.

### Intermediate
**Q: You cut the fee from 10% to 5%. What happens to sales made yesterday?**
A: Nothing. They stay at 10%. Every order item stores its own fee and creator earnings at the moment
of purchase. Balances, statements and payouts all sum those stored columns and never recompute from
today's rate. So history stays true, and a creator's lifetime figure is a correct mix of both rates.

**Q: Why didn't this need a Flyway migration?**
A: The schema doesn't know the rate. The CHECK constraint only says `fee + earnings = price`, which
holds at any rate. A migration would only be needed to rewrite old rows, and we deliberately don't do
that.

### Advanced / follow-up probes
**Q: The rate lives in two places, backend and frontend. What's the risk and how would you fix it?**
A: The risk is drift: someone changes the YAML, and the Terms still promise the old split. That is a
legal problem, not only a UI bug. The fix is to make the server the only source: expose the rate on
a public GraphQL query and render the Terms from it. Or, short of that, add a test that reads the
YAML and asserts it equals `PLATFORM_FEE_PERCENT × 100`.

**Q: Why does `CommerceIT` assert `2_495` literally, when `ProductStatsIT` calls `platformFeeFor`?**
A: They test different things. `CommerceIT` is the known-answer test for the fee maths: if it called
`platformFeeFor` to get its expected value, a bug in the formula would agree with itself. The stats
tests check that aggregation sums the snapshots, so they don't care what the rate is.

**Q: Is the change safe to deploy mid-checkout?**
A: Yes. The fee is computed and stored when the order is created. A buyer who started checkout
before the deploy completes the order at the rate already stored on it.

### "Tell me about a change that turned out smaller than expected"
**Q: Have you ever had a design decision pay off later?**
A: In Phase 4 we chose to snapshot the commission split onto each order item instead of computing
earnings on the fly. Months later the business cut the commission from 10% to 5%. On the backend it
was a one-line config change, with no migration and no risk to past payouts. The only real work was
in the frontend, where "10%" had been typed into four places. I pulled that into one constant, so
the next change is one line too.

---

## 8. Gotchas and bugs we hit

| Symptom | Root cause | Fix | Lesson |
|---|---|---|---|
| Changing the YAML alone would have left Terms, About and two dashboard hints still saying 10% | Rate duplicated as literal strings in four frontend files | `PLATFORM_FEE_PERCENT`, with "95%" derived as `100 − x` | Grep for the *number* (and its complement, 90%), not just the variable name |
| Dashboard "platform fee (5%)" can look wrong for creators with pre-change sales | The lifetime fee is the sum of 10% and 5% snapshots | Accepted for now; noted in §6 | A label on an aggregate describes the *current* rule, not the history |
| `PayoutIT` / `ReceiptIT` fixtures still use 10% numbers | They insert snapshot rows directly; they test stored history, not the rate | Left unchanged on purpose | Not every "10%" in tests is about the rate |

---

## 9. New vocabulary

| Term | One-line meaning |
|---|---|
| Config-driven business rule | A business number kept in configuration so changing it isn't a code change |
| Known-answer test | A test whose expected value is worked out independently, not by calling the code under test |
| Effective rate | Total fee ÷ total gross across history; differs from the current rate after a rate change |

---

## 10. If I had to defend this in a code review

- **Strongest point:** the backend change is one config line, and history is untouched. That is the
  Phase 4 snapshot design paying off, and I verified it by checking that nothing downstream
  recomputes from the rate.
- **Strong point:** four hand-typed frontend copies collapsed into one constant, with the creator's
  share derived from it, so the Terms can't contradict themselves.
- **Weakest point:** the backend rate and the frontend constant are linked only by comments. If the
  rate is ever overridden by an env var in production, the Terms silently go wrong. Fix first: serve
  the rate from the backend (a public query) and drop the frontend constant.
- **Business, not code:** existing creators agreed to Terms that said 10%. Making their deal better
  is low-risk, but the Terms should still carry a "last updated" date and creators should be told.
