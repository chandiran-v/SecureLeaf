# GEMINI.md — SecureLeaf

## Read these first, in this order

1. **[`AI_RULES.md`](AI_RULES.md)** — mandatory working rules for every AI assistant on this repo
2. **[`CLAUDE.md`](CLAUDE.md)** — project context and coding conventions (they apply to you too, despite the filename)
3. **[`docs/requirements.md`](docs/requirements.md)** — numbered functional requirements and MVP scope

---

## ⚠️ The rule most easily missed

The owner is using this project to learn engineering and to prepare for technical interviews. **Every feature, phase, or non-trivial change must ship with a learning note in `docs/learning-notes/`** — written for a beginner, explaining what was built, why, the best practices applied, the alternatives rejected, and interview Q&A at beginner/intermediate/advanced levels.

This is **part of the task, not an optional extra**. Do not wait to be asked. Code that works with no note is a **half-finished** phase.

Start from `docs/learning-notes/TEMPLATE.md`. `AI_RULES.md` has the full standard and the definition of done.

---

## Project in one paragraph

SecureLeaf is a DRM-protected digital content marketplace. Creators upload PDFs; buyers purchase and view them inside a secure browser-based Canvas viewer, never as a downloadable file. Pages are converted server-side to image tiles, watermarked per-request with the buyer's identity, and served via 30-second signed URLs. Stack: Java 21 / Spring Boot 3 / GraphQL / PostgreSQL / Redis / MinIO, and React 18 + TypeScript + Apollo + Tailwind.

## Hard constraints

- **Flyway owns the schema.** `ddl-auto` is `validate`. Never edit an applied migration — add a new one. A validation failure means fix the *entity*, not the migration.
- **Money is `paise`** — integers only, never floats.
- **GraphQL for data, REST only for file upload/download.**
- **Never return `null`** from a service — use `Optional` or a specific business exception.
- **Clean, unwatermarked tiles must never reach a browser**, and MinIO buckets must never be made public.
