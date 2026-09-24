# SecureLeaf - AI Assistant Rules

This file provides context and rules for the AI assistant working on the SecureLeaf project.

## Project Context
- **Name**: SecureLeaf
- **Description**: A DRM-protected digital content marketplace where creators upload PDFs and buyers view them in a secure, non-downloadable canvas viewer.

## Tech Stack
- **Frontend**: React 18, TypeScript, Apollo Client, Vite
- **Backend**: Java 21, Spring Boot 3, Spring Security 6
- **Database**: PostgreSQL 16 (via Flyway migrations)
- **Infrastructure**: Local Docker Compose (Postgres, Redis, MinIO)
- **Production**: Render (Backend), Supabase (DB + Storage), Vercel (Frontend), Upstash (Redis)

## Coding Conventions
### Backend (Java / Spring Boot)
- **Java 21**: Use modern Java features like Records, Pattern Matching, and virtual threads where appropriate.
- **Database**: Always use Flyway for schema migrations. Never let Hibernate auto-generate the DDL (`spring.jpa.hibernate.ddl-auto=validate`).
- **REST / GraphQL**: The project uses both Spring for GraphQL and standard REST. Prefer GraphQL for data fetching and REST for file uploads/downloads.
- **Null Safety**: Avoid returning `null`. Use `Optional` or throw specific business exceptions.
- **Types**: Always use `paise` (smallest currency unit, integers) for monetary values. Use `TIMESTAMPTZ` in Postgres and `OffsetDateTime` or `Instant` in Java for dates.

### Frontend (React / TypeScript)
- **Styling**: Tailwind CSS v3. Use utility classes for rapid development of modern UI patterns (glassmorphism, vibrant gradients, micro-animations). Ensure the design feels premium and state-of-the-art.
- **Components**: Functional components only. Use custom hooks for complex logic.
- **State**: Use Apollo Client for remote GraphQL state and Zustand for local/client state.
- **Strict Typing**: Avoid `any`. Define proper TypeScript interfaces/types for all domain models matching the backend.

### Database (PostgreSQL)
- **Naming**: Use `snake_case` for all tables and columns.
- **Primary Keys**: Use `BIGSERIAL` (mapped to Java `Long`).
- **Soft Deletes**: Use `deleted_at` (TIMESTAMPTZ) for entities like products and users to preserve audit trails and buyer entitlements.

---

## ⚠️ MANDATORY: Learning notes on every change

**Read [`AI_RULES.md`](AI_RULES.md) before starting any work on this repository.**

The owner is using this project to learn engineering and to prepare for technical interviews. **Every feature, phase, or non-trivial change must be accompanied by a learning note in `docs/learning-notes/`** — written for a beginner, explaining what was built, why it was built that way, which best practices were applied, the trade-offs rejected, and interview questions with answers.

This is **part of the task, not an optional extra**. Do not wait to be asked. A phase where the code works but the note is missing is **half-finished**.

Start from `docs/learning-notes/TEMPLATE.md`, and update `README.md`, `interview-prep/glossary.md` and `interview-prep/quick-reference.md` alongside it. `AI_RULES.md` has the full standard and the definition of done.
