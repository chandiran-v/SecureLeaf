# SecureLeaf — Learning Notes

This folder contains the "Learning Notes" for each phase of the SecureLeaf project. As per our core requirements, every major feature or milestone must include one of these documents.

They serve a dual purpose:
1. **Onboarding:** Explaining the architectural decisions and "why" behind the code to new engineers.
2. **Interview Prep:** Breaking down the complex concepts, trade-offs, and best practices into easily digestible formats for technical interviews.

## Current Notes

1. **[Phase 01 — Authentication & Authorization](phase-01-auth.md)**
   - Covers: JWTs, Refresh Tokens, Next.js / Spring Boot security integration.
2. **[Phase 02 — Creator Upload & Async Processing](phase-02-upload-pipeline.md)**
   - Covers: BOLA, Magic Bytes, Async Pipeline, PostgreSQL `FOR UPDATE SKIP LOCKED`.

## Process
When a new phase is complete, duplicate `TEMPLATE.md`, name it appropriately, and fill it out before closing the ticket.

---

## How to use this folder

| If you want to… | Read |
|---|---|
| Understand a phase in depth | `phase-XX-*.md` |
| Cram before an interview | `interview-prep/quick-reference.md` |
| Look up a term | `interview-prep/glossary.md` |
| Write a new phase note | Copy `TEMPLATE.md` |

---

## Index

| Phase | Note | Status | Core topics |
|---|---|---|---|
| 0 | *(schema design — covered in `../db_schema.md`)* | ✅ Done | Normalization, indexes, enums, constraints |
| 1 | [phase-01-auth.md](phase-01-auth.md) | ✅ Done | JWT, refresh token rotation, BCrypt, Spring Security filter chain, OAuth2, RBAC |
| 2 | [phase-02-upload-pipeline.md](phase-02-upload-pipeline.md) | ⏳ Not started | Async processing, job queues, `SKIP LOCKED`, object storage, thread pools, object-level authz |
| 3 | Marketplace | ⏳ Not started | Full-text search, pagination, N+1 queries, DTO projection |
| 4 | Commerce | ⏳ Not started | Idempotency, state machines, audit logs, transactional integrity |
| 5 | Secure Viewer | ⏳ Not started | DRM, signed URLs, Strategy pattern, distributed locks, image processing |
| 6 | Library + polish | ⏳ Not started | Pub/Sub, notifications, caching |

---

## The rule that keeps this folder alive

Every AI-assisted development session on this repo **must** update these notes as part of the work — not afterwards, not on request. That instruction lives in:

- **`/CLAUDE.md`** — read automatically by Claude Code in every session
- **`/GEMINI.md`** — read automatically by Gemini CLI in every session
- **`/.github/copilot-instructions.md`** — read automatically by GitHub Copilot
- **`/AI_RULES.md`** — the single source of truth all three point to

If you switch to another AI tool, point its config file at `AI_RULES.md` too. Do not copy the rules — copies drift.

---

## What makes a good note here

1. **Explain to a beginner, not to yourself.** If a term appears for the first time, define it in one sentence before using it.
2. **Always answer "why", not just "what".** "We hash refresh tokens" is trivia. "We hash refresh tokens so a database leak doesn't hand an attacker working sessions" is an interview answer.
3. **Name the alternative you rejected.** Interviewers probe trade-offs. Every design decision should record what else was on the table and why it lost.
4. **Include real code from this repo**, with file paths and line references — not invented examples.
5. **Write the interview Q&A as you go.** It is far harder to reconstruct months later.
