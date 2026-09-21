# GitHub Copilot instructions — SecureLeaf

Read [`../AI_RULES.md`](../AI_RULES.md) and [`../CLAUDE.md`](../CLAUDE.md) before suggesting changes.

**Mandatory:** every feature or non-trivial change must be accompanied by a beginner-friendly learning note in `docs/learning-notes/` — what was built, why, best practices applied, trade-offs rejected, and interview Q&A. This is part of the task, not an optional extra. Start from `docs/learning-notes/TEMPLATE.md`.

**Key conventions:** Java 21 / Spring Boot 3; Flyway owns all DDL (`ddl-auto: validate`); money in integer `paise`; GraphQL for data and REST only for file transfer; `snake_case` in Postgres; soft deletes via `deleted_at`; no `any` in TypeScript.
