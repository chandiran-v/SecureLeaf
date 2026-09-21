# SecureLeaf — Interview Quick Reference

> The cram sheet. One page per phase, growing as the project grows. Read `../phase-XX-*.md` for the depth behind each line.

---

## The 60-second project pitch

> "SecureLeaf is a DRM-protected marketplace for digital documents. Creators upload PDFs; buyers purchase and read them inside a secure browser viewer, never as a downloadable file. Server-side, each PDF is converted into per-page image tiles by a PostgreSQL-backed async job queue. When a buyer views a page, the tile is fetched, a watermark carrying their identity is burned into it in-memory with Java2D, and it's served over a signed URL that expires in 30 seconds. The browser never receives a clean tile. It's Java 21 / Spring Boot 3 with GraphQL, PostgreSQL, Redis and MinIO, and a React + TypeScript frontend."

**If they ask "why is that hard?"** — three things: the browser must never hold an unwatermarked image, so watermarking happens per-request rather than at upload; tile serving has a sub-500ms budget with watermark burning on the hot path; and access is enforced at the level of individual records, not just roles.

**The honest framing of the DRM:** it is *traceable piracy deterrence*, not absolute prevention. No browser solution can stop a phone camera. Every leaked page carries a watermark identifying the buyer it came from. Same model as Kindle or Spotify. Saying this unprompted signals engineering maturity — claiming "uncrackable DRM" signals the opposite.

---

## Phase 1 — Auth · [full note](../phase-01-auth.md)

| Concept | The one-line answer |
|---|---|
| Two tokens | 15-min JWT access (stateless, unrevocable) + 7-day DB-backed refresh (revocable) |
| BCrypt vs SHA-256 | Slow+salted for guessable human passwords; fast for 128-bit random tokens |
| Rotation | Refresh tokens are single-use; reuse = theft → revoke all sessions |
| 30s grace window | Multiple browser tabs racing a refresh aren't attackers |
| CSRF off | Safe *because* we use `Authorization` headers, not cookies |
| Google OAuth | Verify signature + issuer + **audience**; we never see a password |
| `ddl-auto: validate` | Flyway owns the schema; Hibernate only checks for drift |
| AuthN vs AuthZ vs BOLA | Who are you / what may you do / is *this record* yours |

**Weakest point to volunteer:** tokens in localStorage — XSS-exposed. Correct fix is httpOnly cookies + CSRF tokens.

---

## Phase 2 — Upload & async pipeline · [full note](../phase-02-upload-pipeline.md)

| Concept | The one-line answer |
|---|---|
| `FOR UPDATE SKIP LOCKED` | Lets many workers pull from one queue table without blocking or double-processing |
| Why Postgres, not Kafka | ~10 uploads/day doesn't justify brokers, partitions and Zookeeper ops |
| 202 Accepted | Upload returns immediately; the work happens async — don't hold an HTTP thread for 30s |
| Idempotent stages | Every stage is re-runnable, because retries replay it |
| Magic-byte validation | Trust the file's first bytes, never the client's `Content-Type` |
| `assertOwnership` | The object-level check `@PreAuthorize` can't do |

**Weakest point to volunteer:** the worker polling interval is fixed. Event-driven triggers like Postgres LISTEN/NOTIFY would be more responsive and efficient.

---

## Cross-cutting themes to weave into any answer

1. **Threat-model each decision.** Every security choice here has a "what attack does this stop" answer. Say it.
2. **Name what you gave up.** Every choice has a cost; stating it is what separates a senior answer from a memorized one.
3. **Defense in depth.** Validate in the DTO, enforce in the service, constrain in the database.
4. **Design for concurrency.** The pessimistic lock, `SKIP LOCKED` and the grace window all exist because two things happen at once in production.
5. **Fail closed.** Errors deny access; they never grant it.
