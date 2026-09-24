# AI_RULES.md — Shared rules for every AI assistant on this repo

> **Single source of truth.** `CLAUDE.md`, `GEMINI.md` and `.github/copilot-instructions.md` all point here.
> Do not copy these rules into those files — copies drift and then contradict each other.

---

## Rule 1 — Every phase of work produces a learning note (MANDATORY)

The owner of this repository is using this project to **learn backend and full-stack engineering and to prepare for technical interviews**. The code is only half the deliverable. The other half is `docs/learning-notes/`.

**Whenever you implement a feature, a phase, or any non-trivial change, you must also write or update the matching note in `docs/learning-notes/`. This is part of the task, not an optional extra. Do not wait to be asked. Do not skip it because the user didn't mention it.**

### How

1. Copy `docs/learning-notes/TEMPLATE.md` to `docs/learning-notes/phase-XX-<slug>.md`, or update the existing note if the phase already has one.
2. Fill in **every** section of the template.
3. Add or update the phase's row in `docs/learning-notes/README.md`.
4. Add any newly introduced term to `docs/learning-notes/interview-prep/glossary.md`.
5. Add the phase's one-line takeaways to `docs/learning-notes/interview-prep/quick-reference.md`.

### The standard these notes must meet

Write for a **motivated beginner who will be interviewed on this code in three months**. Specifically:

- **Define before you use.** The first time a term appears, explain it in one sentence. Assume no prior knowledge of Spring Security, JPA, GraphQL, Docker, or Redis.
- **Always answer "why", never only "what".** "We hash refresh tokens" is trivia. "We hash refresh tokens so a database leak doesn't hand an attacker working sessions" is an interview answer.
- **Use analogies for hard concepts,** then give the precise technical version underneath.
- **Name the alternative that was rejected, and why it lost.** Interviewers probe trade-offs; every design decision must record what else was considered and the honest cost of the choice.
- **Quote real code from this repository** with `file/path.java:LINE` references. Never invent illustrative examples.
- **Write the interview Q&A as you go**, graded beginner → intermediate → advanced. Write answers as they would be *spoken*, not as essays.
- **Record what breaks without this.** The failure mode is usually the actual interview question.
- **Be honest about weaknesses.** Each note ends by naming the design's weakest point and how you'd fix it. That is a feature of the note, not a flaw in the work.
- **Log every bug you hit** — symptom, root cause, fix, lesson. Bugs are the best interview stories and are impossible to reconstruct later.

### Definition of done

A phase is **not complete** until:
- [ ] The code works and is verified
- [ ] `docs/learning-notes/phase-XX-*.md` exists and every template section is filled
- [ ] `README.md`, `glossary.md` and `quick-reference.md` are updated
- [ ] The note explains every new concept from scratch
- [ ] Interview Q&A covers beginner, intermediate and advanced levels

If you finish the code and stop, **you have finished half the task.** Say so and write the note.

---

## Rule 2 — Explain as you go, in the chat too

While working, briefly explain *why* you are making each significant choice, not just what you are doing. The user is learning from the session itself, not only from the notes. Keep it proportionate — a sentence or two at each real decision point, not a lecture on every line.

## Rule 3 — Prefer teaching the standard pattern

Where several implementations would work, choose the one that is **industry-standard and interview-recognizable** over a clever bespoke shortcut, and say which pattern it is by name (Repository, Strategy, DTO, job queue, filter chain…). If you do choose the non-obvious path, the notes must explain why.

## Rule 4 — Flag learning moments

When you hit something genuinely subtle — a race condition, an N+1 query, a lazy-loading trap, a security footgun — call it out explicitly as a learning moment and put it in the note's "Gotchas" table, even if the fix was one line.

---

## Rule 5 — Engineering conventions

All coding conventions for this project (Java 21, Flyway-only DDL, `paise` for money, GraphQL vs REST, `snake_case`, soft deletes, no `any` in TypeScript, etc.) live in **`CLAUDE.md`**. Read that file too — it applies regardless of which AI tool you are.

---

## Rule 6 — Documentation hygiene

- Requirements live in `docs/requirements.md`. Do not restate them elsewhere; link to them by requirement ID (`UPLOAD-04`, `VIEW-03`).
- Never edit an applied Flyway migration. Add a new one.
- If you notice a documentation conflict (the repo has a few), flag it rather than silently picking a side.
