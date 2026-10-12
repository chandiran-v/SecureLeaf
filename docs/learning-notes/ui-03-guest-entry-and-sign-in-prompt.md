# UI 03 — Login-first front door, "Continue as guest", and a sign-in prompt

> **Status:** Done
> **Built:** 2026-10-12
> **Requirement IDs covered:** AUTH-05 (role-based access: the **Guest** role still browses and previews, see `docs/requirements.md:7`), MKT free preview (Phase 3).
> **Commits:** _(see the "Guest entry and sign-in prompt" commit on `feature/render-free-tier`)_

---

## 1. What we built, in plain English

When the site went live on Vercel, opening `https://secureleaf-frontend.vercel.app` showed the **marketplace** straight away. The owner wanted visitors to meet the **login page first**, with a way to look around without an account.

Now:

1. **Opening the site** (`/`) while logged out shows the **login page**. Under the form there is a **"Continue as guest →"** link to the marketplace.
2. **A guest can browse** the marketplace and read each book's **free preview pages**, exactly as before.
3. **When a guest tries to do more**, a **pop-up** appears ("Sign in to continue") with three choices: **Sign in**, **Create account**, **Not now**. It appears when they:
   - click **"Log in to buy"** on a product, or
   - reach the last free preview page and click **"Keep reading"** (only shown if the book has more pages than the preview).
4. **Sign in / Create account** take them to the login or register page and, after logging in, **bring them back to the same product**.
5. **Signed-in users** opening `/` still land on the marketplace, as before.

**Before:** `/` was the public marketplace; a guest clicking "Log in to buy" was thrown straight onto the login page with no explanation; the preview's Next button just went grey at the last free page.
**After:** login-first front door with a guest escape hatch; a friendly, dismissible prompt instead of a hard redirect.

---

## 2. Why it matters

Two goals pull against each other:

- **The owner's goal:** make signing in the obvious first step (more accounts, a clearer "this is a members' store" feel).
- **The business constraint:** the marketplace must stay **reachable without an account**. Guests browsing is a requirement (`docs/requirements.md:7`), people share product links, and **Razorpay's KYC review** has to see products and prices on the public site before it activates live payments (Phase 18).

A full **login wall** (every page requires sign-in) would satisfy the first and break the second. So the design is: the *front door* (`/`) is login-first, but the *rooms* (`/marketplace`, `/product/:id`) stay public.

The prompt matters for **conversion**: an unexplained jump to a login form at the moment someone wants to buy feels like an error. A dialog that says *why* and offers "Not now" keeps them on the page.

---

## 3. New concepts introduced

### 3.1 Route guards (and a "front door" guard)

**What it is:** A route guard is a component that sits in front of a page and decides whether to show it or send the user somewhere else.

**The analogy:** A receptionist at a building's main entrance. Members walk through; visitors are pointed to the sign-in desk, but they can still ask to look around the lobby.

**Why we needed it here:** We already had `ProtectedRoute` (Phase 1) for pages that *require* login. `/` is different: a guest isn't *forbidden* from the site, they're just *directed* to login first. So it's a separate, tiny guard.

**In our code:** `frontend/src/components/layout/HomeRoute.tsx:10-13`
```tsx
export default function HomeRoute() {
  const { isAuthenticated } = useAuthStore();
  return isAuthenticated ? <MarketplacePage /> : <Navigate to="/login" replace />;
}
```
and wired in `frontend/src/App.tsx:44`: `<Route path="/" element={<HomeRoute />} />`.

`replace` matters: without it, the browser history would hold `/` → `/login`, and pressing **Back** on the login page would go to `/`, which redirects to `/login` again. The user would be stuck in a loop.

**What breaks without it:** nothing *breaks*; the site simply opens on the marketplace, which is what the owner reported as a bug.

### 3.2 Client-side guards are UX, not security

**What it is:** Anything in the browser can be bypassed (edit the JS, call the API directly). So frontend guards only decide *what to show*; the **server** decides *what is allowed*.

**Why it matters here:** hiding "Buy" behind a pop-up does not protect anything. The real protection is unchanged: `initiateOrder` and the reader endpoints require a valid JWT on the backend, and the preview endpoint only ever serves pages `1..freePreviewPages` (Phase 3). This change is purely about the experience.

**Interview phrasing:** "The route guard is a UX affordance; authorization lives on the server. If I deleted every guard in the React app, a guest still couldn't buy or read a paid page."

### 3.3 "Return to where you were" after login (`state.from`)

**What it is:** When we send someone to log in, we pass the page they were on, so that after login we can send them back.

**The analogy:** A cloakroom ticket. You hand over your place in the site, and get it back after the detour.

**In our code:** `frontend/src/components/auth/SignInPromptModal.tsx:21-24`
```tsx
const goTo = (path: '/login' | '/register') => {
  onClose();
  navigate(path, { state: { from: location } });
};
```
`useAuth` already reads `state.from` and navigates there after a successful login or registration (`frontend/src/hooks/useAuth.ts`, `afterLoginPath`). We reused that: no new mechanism, just a new caller.

**What breaks without it:** the guest logs in and lands on the marketplace home, and has to find the book again. That's a lost sale waiting to happen.

### 3.4 An optional callback prop to keep a component reusable

**What it is:** Instead of making `PreviewPane` know about auth, we give it an optional `onReadMore` function. If the parent passes one, the last page shows "Keep reading" and calls it; if not, the pane behaves exactly as before.

**In our code:** `frontend/src/components/marketplace/PreviewPane.tsx:11` (prop) and `:107` (render), with the decision made by the page, `frontend/src/pages/marketplace/ProductDetailPage.tsx:66`:
```tsx
const guestCanReadMore = !isAuthenticated && (product.pageCount ?? 0) > product.freePreviewPages;
```

**Why:** this is the **"smart parent, dumb child"** (container/presentational) pattern. `PreviewPane` stays a pure "show preview pages" component; the page decides what "read more" means for this user.

---

## 4. Best practices applied

| Practice | What we did | Why it matters | Where |
|---|---|---|---|
| Keep public content public | Only `/` redirects; `/marketplace` and `/product/:id` stay open | Guest role, shareable links, Razorpay KYC review | `App.tsx:44-48` |
| `replace` on redirects | `<Navigate … replace />` | No Back-button loop | `HomeRoute.tsx:12` |
| Reuse the existing return-path mechanism | `state.from`, already read by `useAuth` | One way to do it, not two | `SignInPromptModal.tsx:21-24` |
| Reuse the accessible `Modal` | role=dialog, aria-modal, focus handling, Escape | Keyboard and screen-reader users can use it | `components/ui/Modal.tsx` |
| Dismissible prompt | "Not now" + Escape + backdrop click close it | Never trap a guest | `SignInPromptModal.tsx` |
| Don't offer what doesn't exist | "Keep reading" only if `pageCount > freePreviewPages` | No dead-end prompt on a fully free preview | `ProductDetailPage.tsx:66` |
| Tests for each state | guest → login; signed-in → marketplace; prompt opens/closes; Keep reading shown/not shown | Locks the behaviour in | `HomeRoute.test.tsx`, `ProductDetailPage.test.tsx` |

---

## 5. What does what — file map

| File | Responsibility |
|---|---|
| `frontend/src/components/layout/HomeRoute.tsx` | `/`: guest → `/login`, signed-in → marketplace |
| `frontend/src/App.tsx` | Routes `/` to `HomeRoute`; `/marketplace` stays public |
| `frontend/src/pages/auth/LoginPage.tsx` | Adds "Continue as guest →" link to `/marketplace` |
| `frontend/src/components/layout/AppLayout.tsx` | Logo now links to `/marketplace` (a guest clicking it isn't bounced to login) |
| `frontend/src/components/auth/SignInPromptModal.tsx` | The "Sign in to continue" dialog; passes `state.from` |
| `frontend/src/components/marketplace/BuyPanel.tsx` | Guest "Log in to buy" opens the prompt instead of redirecting |
| `frontend/src/components/marketplace/PreviewPane.tsx` | Optional `onReadMore` → "Keep reading" on the last preview page |
| `frontend/src/pages/marketplace/ProductDetailPage.tsx` | Decides when a guest can "read more"; owns that prompt |

**Request trace — a guest buys a book:**
1. Opens `/` → `HomeRoute` → `<Navigate to="/login" replace>`.
2. Clicks "Continue as guest →" → `/marketplace` (public).
3. Opens a product → reads preview pages → clicks "Log in to buy".
4. `BuyPanel` opens `SignInPromptModal` → "Sign in" → `navigate('/login', { state: { from: /product/42 } })`.
5. Logs in → `useAuth` reads `state.from` → back on `/product/42`, now signed in → "Buy for ₹X".

---

## 6. Design decisions and trade-offs

### Decision: login-first only at `/`, not a full login wall
- **Alternatives considered:** (a) keep the public marketplace as the landing page (original design); (b) full login wall on every page except auth and legal pages.
- **Why we chose this:** it gives the owner's "login first" experience while keeping the guest role, shareable product links and Razorpay's review intact.
- **What we gave up:** a first-time visitor sees a login form before any products, so a few curious visitors may leave instead of clicking "Continue as guest". It also partly reverses Phase 3, whose note celebrated removing a login wall from the homepage (`phase-03-marketplace.md:12`).
- **When we would revisit:** if analytics show visitors bouncing from `/login`, make `/` the marketplace again and put a sign-in banner on it instead.

### Decision: no "guest mode" flag
- **Alternatives considered:** store "this visitor chose guest" in `sessionStorage` (or a Zustand store), so `/` shows the marketplace to guests who already chose.
- **Why we chose this:** the logo and the nav link both point to `/marketplace`, so a guest never needs `/`. No extra state, nothing to keep in sync.
- **What we gave up:** a guest who types the bare domain again is shown the login page again (one click to continue).
- **When we would revisit:** if that repeat step annoys real users.

### Decision: a pop-up instead of an immediate redirect
- **Alternatives considered:** keep `navigate('/login')` on click (old behaviour).
- **Why we chose this:** the owner asked for it, and explaining *why* before moving someone off the page is better UX; "Not now" keeps them browsing.
- **What we gave up:** one extra click for people who already intended to sign in.

---

## 7. Interview questions

### Beginner
**Q: What is a route guard in a React app?**
A: A component in front of a page that decides whether to show it or redirect. Ours, `HomeRoute`, shows the marketplace to signed-in users and redirects everyone else to the login page.

**Q: Why use `replace` when redirecting?**
A: So the redirect doesn't add a history entry. Otherwise Back from the login page goes to `/`, which redirects to login again, and the user is stuck.

### Intermediate
**Q: If the frontend hides the Buy button from guests, is buying secure?**
A: The hiding isn't what makes it secure. The backend requires a valid JWT for `initiateOrder` and for every reader endpoint. The frontend guard is only UX; I could delete it and a guest still couldn't buy.

**Q: How does the user get back to the product after logging in?**
A: When we send them to login we pass the current location as router state, `state.from`. After a successful login, `useAuth` navigates to that path instead of the home page.

**Q: Why not just put the whole site behind login?**
A: Guests browsing is a requirement, product links need to work when shared, and the payment provider has to see products and prices before activating live payments. So only the front door is login-first.

### Advanced / follow-up probes
**Q: Could `state.from` be abused as an open redirect?**
A: No, because it's router state, not a URL query parameter, so an attacker can't set it from a link, and `navigate` only moves within our own app's routes. `useAuth` also refuses to send you back to `/login` or `/register`. If we ever moved it to a `?next=` query parameter, we'd need to validate that it's a same-site path.

**Q: How would you measure whether login-first hurts sales?**
A: Funnel analytics: visits to `/login` → "Continue as guest" clicks vs bounces → product views → purchases. Compare against a period with the marketplace as the landing page, or run an A/B test.

### "Tell me about a bug you fixed"
**Q: Tell me about a time a "bug report" was really a product decision.**
A: After deploying, the owner reported the site "landing on the dashboard instead of login". The code was doing exactly what the requirements said: guests could browse the marketplace. Instead of just adding a login wall, I pointed out the trade-offs (the guest role, shared links, the payment provider's site review) and offered options. We settled on login-first at the front door, a "Continue as guest" link, and a sign-in pop-up when a guest tries to buy or read past the preview. That got the experience the owner wanted without breaking the requirement.

---

## 8. Gotchas and bugs we hit

| Symptom | Root cause | Fix | Lesson |
|---|---|---|---|
| (Avoided) Guest clicks the SecureLeaf logo and is bounced to login | The logo linked to `/`, which is now login-first for guests | Logo links to `/marketplace` | When you change what a route does, grep every link to it |
| (Avoided) Back button loop on login | A redirect without `replace` | `<Navigate replace>` | Redirects should usually replace history |
| (Avoided) "Keep reading" on a book whose preview is the whole book | Showing the button whenever you're on the last preview page | Only when `pageCount > freePreviewPages` | Don't offer an action that leads nowhere |

---

## 9. New vocabulary

| Term | One-line meaning |
|---|---|
| Route guard | A component that decides whether to render a page or redirect |
| Login wall | Requiring sign-in before any content is visible |
| Return path (`state.from`) | The page to send the user back to after login |
| Open redirect | A vulnerability where an attacker-controlled URL parameter sends users to another site |
| Container / presentational components | A "smart" parent decides behaviour; a "dumb" child just renders and calls callbacks |

---

## 10. If I had to defend this in a code review

- **Strong:** public content stays public; only the front door changed, so requirements, sharing and Razorpay's review still work.
- **Strong:** reuses existing pieces (`Modal`, `state.from` in `useAuth`) instead of inventing new ones; `PreviewPane` stays auth-agnostic via an optional callback.
- **Strong:** covered by tests for each state (guest/signed-in, prompt open/close, Keep reading shown/hidden).
- **Weakest point:** a login form before any products may cost first-time visitors. I'd add analytics on the `/login` → "Continue as guest" funnel and be ready to flip `/` back to the marketplace with a sign-in banner.
