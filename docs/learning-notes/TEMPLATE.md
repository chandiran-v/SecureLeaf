# Phase XX — <Name>

> **Status:** <In progress / Done>
> **Built:** <date range>
> **Requirement IDs covered:** <e.g. UPLOAD-01..11, DASH-01..04 — from `docs/requirements.md`>
> **Commits:** <hashes>

---

## 1. What we built, in plain English

<Two or three paragraphs a non-engineer could follow. No jargon. Describe the user-visible outcome first, then the machinery behind it.>

**Before this phase:** <what didn't work>
**After this phase:** <what now works>

---

## 2. Why it matters

<What problem does this solve? Why is it built now rather than later? What would break if we skipped it?>

---

## 3. New concepts introduced

> One subsection per concept. This is the section you will reread before an interview.

### 3.1 <Concept name>

**What it is:** <one or two sentences, no jargon>

**The analogy:** <a concrete real-world comparison>

**Why we needed it here:** <tie it to this specific phase>

**How it works:**
<mechanism, with a diagram or numbered steps if it helps>

**In our code:** `path/to/File.java:LINE`
```java
// a short, real excerpt from this repo
```

**What breaks without it:** <the failure mode — this is what interviewers actually ask about>

---

## 4. Best practices applied

| Practice | What we did | Why it matters | Where |
|---|---|---|---|
| | | | `path:line` |

---

## 5. What does what — file map

> A reader should be able to trace one full request through the system using this table.

| File | Responsibility |
|---|---|
| | |

**Request trace — `<the main operation of this phase>`:**
1. `<entry point>` →
2. `<next layer>` →
3. `<…>` →
4. `<result>`

---

## 6. Design decisions and trade-offs

### Decision: <what we chose>
- **Alternatives considered:** <the other options>
- **Why we chose this:** <reasoning>
- **What we gave up:** <the honest cost>
- **When we would revisit:** <the trigger condition>

---

## 7. Interview questions

> Ordered easy → hard. Write the answer you would actually say out loud, not an essay.

### Beginner
**Q: <question>**
A: <answer>

### Intermediate
**Q: <question>**
A: <answer>

### Advanced / follow-up probes
**Q: <question>**
A: <answer>

### "Tell me about a bug you fixed"
**Q: <question>**
A: <situation → what was wrong → how you found it → the fix → what you learned>

---

## 8. Gotchas and bugs we hit

| Symptom | Root cause | Fix | Lesson |
|---|---|---|---|
| | | | |

---

## 9. New vocabulary

| Term | One-line meaning |
|---|---|
| | |

---

## 10. If I had to defend this in a code review

<Three or four bullets: the strongest points of this phase's design, and the weakest one you would fix first given more time. Interviewers respect a candidate who knows their own code's weak spot.>
