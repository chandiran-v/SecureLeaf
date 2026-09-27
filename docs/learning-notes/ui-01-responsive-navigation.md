# UI 01 — Responsive navigation (mobile menu)

> **Status:** Done
> **Built:** 2026-09-27
> **Requirement IDs covered:** none directly. It's usability across devices, needed by every buyer/creator flow (LIB-01, DASH-01…).
> **Commits:** _(see the "Mobile navigation menu" PR)_

---

## 1. What we built, in plain English

On a phone, the header's links (Marketplace, My Library, Creator Dashboard / Become a Creator, Admin) simply **disappeared**. The code hid them below 640 px and put nothing in their place, so a mobile user couldn't reach their library or dashboard at all.

Now, below 768 px, the header shows the logo, the notification bell and a **menu button (☰)**. Tapping it drops down a panel with every link, plus your name and **Log out**. It closes when you tap a link, press Escape, or tap outside it. On a laptop the links sit inline in the header, as before.

**Before:** no navigation on phones. **After:** full navigation on every screen size.

---

## 2. Why it matters

A large share of users browse on phones. A feature that exists but can't be reached is, to them, a feature that doesn't exist. It's also a classic review finding: `hidden sm:flex` with no mobile alternative is an easy mistake, because on a developer's wide screen everything looks fine.

---

## 3. New concepts introduced

### 3.1 Mobile-first breakpoints in Tailwind

**What it is:** Tailwind classes apply to *all* widths by default. A prefix like `md:` means "from 768 px **up**". So `hidden md:flex` means "hidden on phones, flex from tablets up", and `md:hidden` means "visible on phones only".

**The analogy:** You write the phone layout first, then add "when there's more room, also do this".

**In our code:** `frontend/src/components/layout/AppLayout.tsx`: the desktop `<nav className="hidden md:flex">` and the menu button `className="md:hidden …"`.

**What breaks without it:** exactly this bug. Hiding something with `hidden sm:…` without adding a small-screen alternative removes it from phones entirely.

**Why `md` and not `sm`:** four links + bell + name + Log out don't fit comfortably at 640 px, and they wrapped or crowded. 768 px is where they fit.

### 3.2 The disclosure pattern (an accessible show/hide menu)

**What it is:** A button that shows or hides a region. For screen-reader users it has to *announce* its state:
- `aria-expanded="true|false"` on the button, so the user hears "expanded" or "collapsed";
- `aria-controls="mobile-menu"`, pointing at the region it opens;
- a label that describes the action: "Open menu" / "Close menu". An icon alone reads as nothing.

**Expected behaviours** users rely on: Escape closes it, tapping outside closes it, and choosing an item closes it.

**In our code:** `AppLayout.tsx`: the menu button, the `Escape` `keydown` effect, the backdrop `div` that closes on click, and the `useEffect` on `location.pathname` that closes it after navigation.

### 3.3 One source of truth for both menus

**What it is:** The desktop links and the mobile links are both rendered from a single `navItems` array built from the user's roles.

**What breaks without it:** two hand-written lists drift. Someone adds "Admin" to the desktop bar and forgets the mobile menu, and admins on phones lose the link.

---

## 4. Best practices applied

| Practice | What we did | Why it matters | Where |
|---|---|---|---|
| Accessible disclosure | `aria-expanded`, `aria-controls`, text labels | Screen readers announce state and purpose | `AppLayout.tsx` menu button |
| Big tap targets | Mobile links are `py-3` full-width rows | Thumb-friendly (~44 px minimum) | mobile `NavLink` class |
| Active-page highlight | `NavLink` `isActive` styling in both menus | Users see where they are | `desktopLinkClass` / `mobileLinkClass` |
| Render only when open | Mobile panel isn't in the DOM while closed | No duplicate links for assistive tech or tests | `{menuOpen && …}` |
| Single source of truth | `navItems` drives both menus | Role rules can't diverge | `navItems` |

---

## 5. What does what — file map

| File | Responsibility |
|---|---|
| `frontend/src/components/layout/AppLayout.tsx` | Header, desktop nav, mobile menu button + panel, close behaviours |
| `frontend/src/components/layout/AppLayout.test.tsx` | Admin-link rules (Phase 8) and 6 mobile-menu tests |

**Trace — opening the menu on a phone:** tap ☰ → `setMenuOpen(true)` → the panel renders with links from `navItems` → tap "My Library" → `onClick` closes it and the router navigates → the `location.pathname` effect guarantees it's closed on the new page.

---

## 6. Design decisions and trade-offs

### Decision: a drop-down panel under the header, not a slide-in drawer
- **Alternatives considered:** a full-height side drawer; a bottom tab bar.
- **Why we chose this:** four links fit in a short panel; no focus-trapping drawer machinery is needed; the header (and notification bell) stay visible.
- **What we gave up:** no swipe gesture; with many more links a drawer would scale better.
- **When we would revisit:** if the nav grows past ~6 items, or if we add a mobile-app-like experience.

### Decision: Log out moves into the menu on phones
- **Why:** the header is too narrow for bell + name + Log out + menu button. Log out is a rare action, so one extra tap is fine.

---

## 7. Interview questions

### Beginner
**Q: What does `hidden md:flex` mean in Tailwind?**
A: Hidden by default, which means on phones, and `display: flex` from the `md` breakpoint (768 px) up. Tailwind is mobile-first: unprefixed classes are the phone styles, and prefixes add styles for bigger screens.

### Intermediate
**Q: What makes a hamburger menu accessible?**
A: A real `<button>` with a text label like "Open menu", `aria-expanded` reflecting its state, and `aria-controls` pointing at the panel. And the standard behaviours: Escape closes it, clicking outside closes it, and choosing an item closes it.

### Advanced / follow-up probes
**Q: Why render the mobile panel only when it's open, instead of hiding it with CSS?**
A: If it's always in the DOM, every link exists twice. A screen reader lists duplicates, and tests that look up "the Admin link" become ambiguous. Rendering it conditionally keeps one copy of each link at any time. The trade-off is you can't animate its exit without extra work.

### "Tell me about a bug you fixed"
**Q: Tell me about a UI bug that only showed on some devices.**
A: The header used `hidden sm:flex` on the nav, so below 640 px the links vanished with no replacement: on a phone you couldn't reach your library or dashboard. On a laptop it looked perfect, so nobody noticed until someone tested on a phone. I added a disclosure menu for small screens, driven from the same list of links as the desktop bar so the two can't drift. The lesson: whenever you hide something at a breakpoint, ask "what replaces it?"

---

## 8. Gotchas and bugs we hit

| Symptom | Root cause | Fix | Lesson |
|---|---|---|---|
| (manual testing) On a phone, no way to reach My Library or Creator Dashboard | `nav` was `hidden sm:flex` with no small-screen alternative | Menu button + drop-down panel below `md` | Every `hidden <bp>:…` needs a replacement below that breakpoint |
| Header crowded between 640–768 px once the menu button existed | 4 links + bell + name + Log out don't fit at `sm` | Switched the desktop/mobile split to `md` | Pick breakpoints from content, not habit |

---

## 9. New vocabulary

| Term | One-line meaning |
|---|---|
| Mobile-first | Write styles for the smallest screen first; add rules for larger screens with breakpoint prefixes |
| Breakpoint | A screen width at which the layout changes (Tailwind `md` = 768 px) |
| Disclosure pattern | A button that shows/hides a region, announcing state via `aria-expanded` |
| `aria-controls` | Attribute linking a control to the element it affects |

---

## 10. If I had to defend this in a code review

- **Strong:** one `navItems` list for both menus; accessible disclosure; closes on navigate / Escape / outside tap; tested for every role.
- **Strong:** no new dependency, just Tailwind and React state.
- **Weakest point:** keyboard focus isn't moved into the panel when it opens, and isn't returned to the button when it closes. It's fine for a short panel directly under its button, but full WAI-ARIA menu-button keyboard support (arrow keys, focus management) would be the next improvement.
