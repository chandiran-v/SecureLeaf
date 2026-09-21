# Glossary

> Every term defined the first time it appears in a phase note lands here. One line each — follow the link for depth.

| Term | Meaning | First seen |
|---|---|---|
| **Bearer token** | "Whoever holds this is authorized"; sent as `Authorization: Bearer <token>` | [Phase 1](../phase-01-auth.md) |
| **BOLA** | Broken Object Level Authorization — acting on records that aren't yours. OWASP API #1 | [Phase 1](../phase-01-auth.md) |
| **CORS** | Browser rules governing which origins may read a response | [Phase 1](../phase-01-auth.md) |
| **CSRF** | Tricking a browser into sending an authenticated request the user didn't intend | [Phase 1](../phase-01-auth.md) |
| **Claim** | A key/value fact inside a JWT (`sub`, `roles`, `exp`) | [Phase 1](../phase-01-auth.md) |
| **Fail closed** | On error, deny access — never grant it | [Phase 1](../phase-01-auth.md) |
| **Filter chain** | The ordered pipeline every HTTP request passes through in Spring Security | [Phase 1](../phase-01-auth.md) |
| **Idempotent** | Doing it twice has the same effect as doing it once | [Phase 1](../phase-01-auth.md) |
| **JWT** | Signed (not encrypted) token carrying identity claims | [Phase 1](../phase-01-auth.md) |
| **Lazy loading** | Hibernate fetching a relation only when touched; fails outside the session | [Phase 1](../phase-01-auth.md) |
| **N+1 query** | 1 query for a list plus N more for each item's relation | [Phase 1](../phase-01-auth.md) |
| **Pessimistic lock** | `SELECT ... FOR UPDATE` — block others until this transaction commits | [Phase 1](../phase-01-auth.md) |
| **RBAC** | Role-Based Access Control | [Phase 1](../phase-01-auth.md) |
| **Reuse detection** | Treating a second use of a one-time token as evidence of theft | [Phase 1](../phase-01-auth.md) |
| **Salt** | Random value mixed into a hash so identical inputs differ | [Phase 1](../phase-01-auth.md) |
| **Stateless auth** | The server keeps no session; the token carries the identity | [Phase 1](../phase-01-auth.md) |
| **Token rotation** | Replacing a refresh token on every use | [Phase 1](../phase-01-auth.md) |
| **User enumeration** | Learning which accounts exist from differing responses | [Phase 1](../phase-01-auth.md) |
| **Work factor** | BCrypt's tunable slowness dial | [Phase 1](../phase-01-auth.md) |
| **Magic Bytes** | The first few bytes of a file that uniquely identify its true format | [Phase 2](../phase-02-upload-pipeline.md) |
| **SKIP LOCKED** | Database-level concurrency control for high-throughput job queues | [Phase 2](../phase-02-upload-pipeline.md) |
