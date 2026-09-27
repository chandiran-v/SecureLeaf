# Phase 05 — Secure viewer (backend + frontend)

> **Status:** Done — 05A (backend) and 05B (frontend) are both complete
> **Built:** 2026-09-25 (05A) · 2026-09-25 (05B)
> **Requirement IDs covered:** VIEW-01..13 (`docs/requirements.md`) — 05A covers VIEW-01, 02, 03, 10, 12, 13; 05B covers VIEW-04..09, 11 (plus the UI side of 01/10/12)
> **Design docs:** [`docs/phases/phase-05a-secure-viewer-backend.md`](../phases/phase-05a-secure-viewer-backend.md) (D1–D14) and [`docs/phases/phase-05b-secure-viewer-frontend.md`](../phases/phase-05b-secure-viewer-frontend.md) (D1–D10) — decisions are referenced by letter+number throughout, and 05B's D-numbers restart from D1 in its own doc, so a bare "D6" below means backend D6 unless the surrounding text says "05B's D6"
> **Commits:** `auto/issue-4` (05A) and `auto/issue-5` (05B) — see `git log` on each branch

> **Sections 1–10 below were written for 05A.** Rather than duplicate the whole note, 05B's material is added as new subsections inside each existing section (3.8 onward, a second "05B" block in §4/§6/§7/§8/§9, and an updated §10). Read 05A's part first — the frontend leans on almost every concept it introduces.

---

## How to study this note

Read it once, in order. Then go straight to **section 7** and answer every question out loud before reading the printed answer. Section 10 names this design's weakest point on purpose — that is usually the actual interview question, not a flaw to hide.

---

## 1. What we built, in plain English

After Phase 4, a buyer can pay for a product and holds a permanent **entitlement** — a database row that says "this person may read this". But there was nothing yet that would actually *let* them read it. Phase 3 built a **free preview**: a few watermarked pages anyone can see, no login required. Phase 05A builds its paid, buyer-specific sibling: the server side of the **DRM viewer**.

Four things had to exist together for that to be safe:

1. **A viewer session.** Opening the viewer starts a session tied to one buyer and one product. Opening it again — say, on a second laptop — **takes over**, and the first session dies. Only one device can be "in" the book at a time.
2. **Signed, single-use, 30-second tile URLs.** The viewer never gets a permanent link to a page. Every page turn asks the server for a brand-new URL that expires in 30 seconds and can only ever be used once.
3. **A watermark that names the buyer.** Every page image that reaches the browser has the buyer's email, ID, a timestamp and the session ID burned into it — the same idea as Phase 3's preview watermark, but now personal instead of a generic "PREVIEW" stamp.
4. **An audit log.** Every successful page view writes one row: who, what page, when, from where.

**Before this phase:** a buyer with an ACTIVE entitlement had no way to actually view the pages they paid for.
**After this phase:** they can open a session, page through the document, and every single tile they see is watermarked with their identity, individually signed, usable exactly once, and logged. Nothing in this phase draws the pages on screen yet — that's Phase 05B. This phase makes sure that whatever draws them can never get a clean page.

### 1B. What 05B added

05A built a vault with no door handle a buyer could actually turn. 05B is the door handle: the `/read/:productId` page a buyer lands on when they click "Read" in their Library. It does four things:

1. **Draws pages on `<canvas>` only** — never an `<img>`, never a CSS background, never an object URL. Every page is fetched with the buyer's JWT, decoded off-screen, and painted directly into pixels.
2. **Keeps the session alive** — a heartbeat every 15 seconds, and a "this book was opened somewhere else" takeover screen the moment a second device wins the slot.
3. **Turns pages** — Prev/Next, arrow keys, a page indicator, and a one-page-ahead prefetch so turning feels instant instead of waiting on a network round trip every time.
4. **Raises the cost of casual copying** in the browser itself — no right-click, no text selection, no drag-out, no Ctrl/Cmd+P or +S, a blur the instant the tab loses focus or DevTools looks open, and a best-effort blank on PrintScreen. Every one of these is named honestly in §3.13 below, including exactly how to defeat each one — because a control that claims more than it delivers is worse than no control at all.

**Before 05B:** the Library's "Read" button was permanently disabled ("coming in Phase 5"), and `BuyPanel` told an owner they were "In your library" with nowhere further to go.
**After 05B:** both link straight into a working, full-screen secure reader.

---

### 1C. Zoom (added after 05B)

Zoom was out of scope in the 05B spec, and a real reader missed it straight away: small print was hard to read, and the browser's own zoom (Ctrl +) didn't help. The page just re-fitted the bigger window, and could trip the DevTools blur. The reader now has **− / % / + / Fit** in the toolbar, the `+` `-` `0` keys, and **Ctrl + mouse wheel / trackpad pinch** over the page, from 50% to 300%. Zoomed pages scroll, and can be dragged to pan. Zoom stays the same when you turn pages and resets when you open another book.

**Before:** the page was locked to "fit the screen". **After:** readers choose the size, and the browser's own zoom is kept out of the reader.

---

## 2. Why it matters

- **This is the actual product.** SecureLeaf's entire pitch is "buy once, never get a raw file". Everything before this phase — auth, upload, search, checkout — exists to get a buyer to this moment. If this phase leaks a clean page, the business has no product.
- **The threat isn't "can a screenshot happen".** It can, always, on any platform (Kindle, Spotify, Netflix all accept this). The threat this phase actually defends against is *casual, effortless redistribution*: right-click-save, a shared permanent link, a downloadable PDF. What's left after this phase — someone has to deliberately screenshot every page and reassemble it, and every screenshot says exactly who leaked it.
- **It reuses, rather than reinvents, three things Phase 3/4 already proved out:** the `WatermarkRenderer` Strategy interface (3.5 in Phase 3's note), the "never presign a clean object" rule, and HMAC signing with a constant-time compare (Phase 4's `RazorpaySignatures`). Recognising when a new phase is *the same problem again* is itself a skill worth showing in an interview.
- **What would break if we skipped the hard parts:** no session takeover → a buyer could share one login and watch it from ten devices simultaneously. No expiry/single-use on tile URLs → a URL pasted into a public forum works forever, for anyone. No fresh entitlement check per tile → revoking a buyer's access mid-session wouldn't actually stop them. No audit log → a leaked page could never be traced to anyone.

---

## 3. New concepts introduced

### 3.1 The DRM threat model: what we stop, what we don't

**What it is:** DRM (Digital Rights Management) here does not mean "unbreakable". It means "raise the cost and traceability of leaking far above zero, for as many people as possible, without making the product miserable to use."

**The analogy:** a hotel room key card. It won't stop a determined thief with a crowbar. It stops casual opportunism — someone trying every door on the floor — and every entry is logged with a timestamp, so if something *does* go wrong, you know which key opened that door and when.

**Why we needed it here:** a screen can always be photographed. No amount of server-side cleverness changes that. So the design question isn't "how do we make copying impossible" (impossible to answer honestly), it's "what do we make *expensive and attributable*":

- **Stopped:** casual right-click-save (no `<img>`, canvas-drawn bytes in 05B). Sharing a permanent link (URLs expire in 30s and are single-use). Watching from many devices on one login (session takeover, D3). Continuing to read after a refund (fresh entitlement check on every tile, D6 step 6). Anonymous leaking (every tile is watermarked with the buyer's identity, D7).
- **Not stopped, and never claimed to be:** screen recording, a phone camera pointed at the monitor, a browser extension that hooks into canvas rendering. These exist for Netflix and Kindle too.
- **The honest framing to say out loud in an interview:** "This is traceable deterrence, the same model as every mainstream DRM product. If someone screenshots every page of a book and reassembles it, we can't stop that — but every one of those screenshots has that buyer's email burned into it, so redistributing it is now something they'd have to want to get caught for."

**What breaks without this framing:** claiming "our DRM can't be beaten" is the fastest way to sound junior to an interviewer who has broken DRM for a hobby. Everyone in this space knows the honest limit; naming it unprompted is a signal of maturity, not weakness.

---

### 3.2 HMAC-signed URLs vs. presigned storage URLs

**What it is:** both look the same from the browser's side — a URL with a signature and expiry in the query string — but they prove two completely different things.

**The analogy:** a presigned storage URL is a locker key that opens locker #42 directly, valid for an hour, no questions asked once you have the key. An HMAC-signed *application* URL is a numbered ticket that a bouncer checks against a guest list, an ID, and tonight's date before letting you into a room that doesn't have lockers at all — the bouncer fetches what you're allowed to see, on the spot, every time.

**Why we needed it here:** `StorageService.presignedGetUrl` already exists (Phase 3), and it would be the "easy" way to give the browser a tile: presign the object, done. But a presigned URL for a tile bucket object points at the **clean, unwatermarked file**. Handing that out, even for 30 seconds, is the one thing this whole phase exists to prevent — the browser must never hold a byte sequence that isn't already watermarked.

**How it works:**
1. `viewerPageUrl` doesn't presign anything. It returns `/api/viewer/tiles/{sessionId}/{pageNumber}?exp=...&sig=...` — a URL into **our own** `SecureTileController`, not into MinIO.
2. `sig = HMAC-SHA256(drm.signing-secret, "{sessionId}|{pageNumber}|{userId}|{exp}")` — [`TileUrlSigner.java:44`](../../backend/src/main/java/com/secureleaf/viewer/security/TileUrlSigner.java#L44).
3. Every hit on that URL re-runs the D6 checklist (signature, single-use, session, entitlement, page range) **and only then** fetches the clean tile from storage, watermarks it in memory, and returns the watermarked bytes. The clean bytes never leave the JVM.

**In our code:** [`TileUrlSigner.java:43-47`](../../backend/src/main/java/com/secureleaf/viewer/security/TileUrlSigner.java#L43)
```java
public Signature sign(long sessionId, int pageNumber, long userId) {
    long exp = clock.instant().getEpochSecond() + drmProperties.signedUrlTtlSeconds();
    return new Signature(exp, hmac(sessionId, pageNumber, userId, exp));
}
```

**What breaks without it:** a presigned tile URL, even a 30-second one, is a direct line to the clean file. Anyone who captures that URL (a browser extension, a proxy, a curious buyer with devtools open) gets the unwatermarked page — the one thing that must never happen.

**Interview answer to "why not just give the browser a presigned MinIO URL?"** — see this section's title. Say the locker-vs-bouncer line, then the one sentence: *"presigning proves you may fetch an object; it can't prove the object gets watermarked first, and that's the one guarantee this whole feature exists to make."*

---

### 3.3 Single-use tokens (`SET NX`) vs. last-writer-wins (`SET ... GET`)

**What it is:** two different Redis write patterns that look almost identical (`SET` with an option flag) but implement opposite policies.

**The analogy:** `SET NX` is a raffle: whoever's entry the system sees *first* wins, every later entry with the same number is rejected. `SET ... GET` is a "current occupant" sign on a meeting room: whoever walks in *last* takes the room, and the sign tells you who was there before so you can go kick them out.

**Why we needed both, in the same phase, for opposite reasons:**

- **D3 — one active session per buyer+product, last-writer-wins.** VIEW-10 says opening the viewer on a *second* device must **evict** the first, not be rejected by it. If we used `SET NX` here, the *first* device to open the book would permanently "own" the key, and the buyer's own second device would be locked out of their own purchase. That's the wrong policy for this problem — the newest login should always win.
- **D5 — single-use tile signatures, first-writer-wins.** A signed tile URL must be usable exactly once. Here the *first* request to present a given signature is the legitimate one; anything after that — a replay, a URL pasted somewhere, a double-fetch bug — must be rejected. `SET NX` (set-if-not-exists) is exactly "first one wins, everyone else is rejected".

**How it works — D3, the session takeover:** [`ViewerSessionService.java:59-66`](../../backend/src/main/java/com/secureleaf/viewer/service/ViewerSessionService.java#L59)
```java
private static final DefaultRedisScript<String> ACTIVATE_SESSION_SCRIPT = new DefaultRedisScript<>(
        "return redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2], 'GET')", String.class);
```
`SET key value EX 45 GET` is atomic and does two things in one round trip: installs the new session as the key's value, **and** returns whatever value was there before (or `nil`). `startViewerSession` uses that returned old value to know exactly which session, if any, to mark `SUPERSEDED` — [`ViewerSessionService.java:118-125`](../../backend/src/main/java/com/secureleaf/viewer/service/ViewerSessionService.java#L118).

**How it works — D5, the single-use signature:** [`SecureTileService.java:75-79`](../../backend/src/main/java/com/secureleaf/viewer/service/SecureTileService.java#L75)
```java
String usedKey = USED_SIG_KEY_PREFIX + request.signature();
Boolean firstUse = redisTemplate.opsForValue().setIfAbsent(usedKey, "1", USED_SIG_TTL);
if (firstUse == null || !firstUse) {
    throw new BusinessException(ErrorCode.SIGNED_URL_INVALID, "This signed URL has already been used.");
}
```
`setIfAbsent` is Spring Data Redis's name for `SET key value NX` — it only succeeds the first time. The key's 60-second TTL (double the URL's own 30-second life) means Redis cleans itself up; we never need a sweeper for used signatures.

**What breaks without picking the right one:** using `NX` for the session pointer would lock a buyer out of their own second device. Using last-writer-wins (`SET ... GET`, no uniqueness check) for the tile signature would let anyone replay a captured URL as many times as they liked within its 30-second window.

---

### 3.4 Leases and heartbeats

**What it is:** a *lease* is "you may hold this until time T, unless you renew before then". A *heartbeat* is the client's periodic "I'm still here, please renew" ping. Together they let a server detect "the client vanished" without the client ever having to explicitly say goodbye — because a closed laptop lid, a crashed tab, or a dead network connection can't send a goodbye message.

**The analogy:** a library's "still checked out" light on a study room. You press a button every 15 minutes to keep the light on; if you leave without checking out and stop pressing it, the light goes off on its own within a fixed window, and the room becomes free — nobody had to notice you left.

**Why we needed it here:** we need to know a session is dead (so a re-login on another device — or the same device after a crash — isn't wrongly told "someone else is reading this") even when the buyer just closes their laptop mid-chapter and never calls `endViewerSession`.

**How it works:**
1. The (Phase 05B) client calls `viewerHeartbeat` every **15 seconds** (`drm.session.heartbeat-interval-seconds`).
2. Each successful heartbeat refreshes the Redis active-session key's TTL to **45 seconds** (`drm.session.lease-seconds`) — three heartbeat intervals, so one dropped ping doesn't cost you the session.
3. The refresh must be **atomic with the ownership check** — plain `GET` then `EXPIRE` from application code would race against a different session's `startViewerSession` landing in between. A tiny Lua script does both in one round trip: [`ViewerSessionService.java:68-79`](../../backend/src/main/java/com/secureleaf/viewer/service/ViewerSessionService.java#L68)
   ```lua
   local current = redis.call('GET', KEYS[1])
   if current == false then
       return 0                              -- EXPIRED: key is gone
   elseif current == ARGV[1] then
       redis.call('EXPIRE', KEYS[1], ARGV[2]) -- still ours: refresh
       return 1
   else
       return 2                               -- someone else's turn now: SUPERSEDED
   end
   ```
4. A `@Scheduled` sweeper runs every 60 seconds and ends any DB row whose `last_heartbeat_at` is older than the lease — the safety net for a buyer who simply walks away and never calls heartbeat again: [`ViewerSessionService.java:189-207`](../../backend/src/main/java/com/secureleaf/viewer/service/ViewerSessionService.java#L189).

**Why heartbeat itself doesn't write `ended_at`.** `heartbeat()` reports `EXPIRED` the moment Redis notices the key is gone, but deliberately leaves the DB row open — the sweeper (or a later `startViewerSession`'s supersede step) is the only thing that ever writes `ended_at`/`end_reason`. Two independent code paths racing to "close" the same row for two different reasons is exactly the kind of bug this phase's own D3/D5 lessons are about avoiding — so there's only ever **one writer** per reason.

**What breaks without it:** without a lease, "is this session still active" would have no way to expire on its own — a buyer who closes their laptop would appear to hold the session forever, permanently locking out their other devices, and there'd be no way to tell "still reading" from "walked away three days ago" without an explicit sign-off nobody can guarantee.

---

### 3.5 Constant-time comparison

**What it is:** covered in depth in Phase 4's note (§3.10) for `RazorpaySignatures.matches` — this phase reuses the exact same reasoning, not the exact same code, because the signature scheme is different (HMAC over `sessionId|pageNumber|userId|exp`, not over a webhook body).

**Why it matters again here:** `TileUrlSigner.verify` recomputes the expected signature and compares it to what the caller sent. `String.equals` returns as soon as it finds a differing character, so how long the comparison takes leaks how many leading bytes were correct — an attacker who can measure response timing precisely enough could in principle recover a valid signature one byte at a time, without ever knowing the signing secret.

**In our code:** [`TileUrlSigner.java:62-64`](../../backend/src/main/java/com/secureleaf/viewer/security/TileUrlSigner.java#L62)
```java
return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8),
        providedSignature.getBytes(StandardCharsets.UTF_8));
```
`MessageDigest.isEqual` always inspects every byte regardless of where a mismatch first occurs, so the comparison's duration carries no information about *how wrong* a guess was.

**What breaks without it:** in principle, a timing side-channel that turns "guess the whole 256-bit signature" into "guess it one byte at a time" — dramatically cheaper for an attacker, even if still impractical over a real network. The fix costs nothing, so there's no reason not to take it.

---

### 3.6 Append-only audit logs, again — and why *this* one is synchronous

**What it is:** the same idea as Phase 4's `payment_events` (§3.6 there) — a table that only ever grows, recording *what happened* rather than just *the current state*. `viewer_access_logs` is this phase's version: one row per successful page view.

**Why we needed it here (D9, VIEW-13):** "who read what, when, from where" is the traceability half of the DRM story from §3.1. A `viewer_sessions` row tells you a buyer opened the book; it can't tell you they actually looked at page 47 at 3:14pm from a specific IP. If a leaked page ever needs tracing back further than the watermark alone provides, this table is the trail.

**How it works — and the interesting decision:** unlike `payment_events`, which is protected by a database trigger that rejects UPDATE/DELETE, `viewer_access_logs` has no such trigger. It's append-only *by convention* (nothing in the codebase ever updates or deletes a row) rather than *by database enforcement*. That's a smaller guarantee than Phase 4's, and it's a deliberate scope cut for MVP rather than an oversight — see §6.

The write itself happens **synchronously, in the same request and the same transaction** as the tile response — the exact opposite of Phase 4's notifications, which are deliberately deferred to `AFTER_COMMIT` and a separate thread pool (§3.14 there). [`SecureTileService.java:116-118`](../../backend/src/main/java/com/secureleaf/viewer/service/SecureTileService.java#L116):
```java
// D9 — only successful responses reach here; every throw above logs nothing.
viewerAccessLogService.record(session, documentVersion, contentPage, request.pageNumber(),
        request.ipAddress(), request.userAgent(), request.correlationId());
```

**Why synchronous here, but async for Phase 4's notifications?** Notifications trigger a visible side effect (an email) that must never fire for a purchase that later rolls back — so they wait for `AFTER_COMMIT` and run off the request thread so a slow SMTP server can't slow down checkout. An access-log row has no such danger: it's *part of* what makes the response "successful" (VIEW-13 requires every successful tile to have exactly one log row), there's nothing to roll back around, and at MVP's request volume, one extra `INSERT` per page-turn is a rounding error next to watermark rendering. **The trigger to revisit this:** the moment access-log writes show up as measurable request latency (real load, not a handful of testers), or the log needs to survive the tile service itself being down — that's when it becomes an outbox-and-poller, the same pattern Phase 4's note names for emails.

**What breaks without it:** no way to answer "did this buyer actually view the page whose watermark is now circulating, and when" — the whole point of pairing a watermark with an audit trail.

---

### 3.7 Defense in depth: JWT *and* signature *and* session *and* entitlement

**What it is:** stacking several *independent* checks, each of which alone would be enough to stop most attacks, so that defeating the whole system requires defeating all of them at once — not just finding the weakest one.

**The analogy:** a bank vault doesn't rely on the lock alone. It has the lock, a guard who checks ID, a camera, and a silent alarm. Beating the lock gets you nowhere if the guard still checks your face.

**Why we needed it here (D6):** a signed URL alone would be enough to stop *random* guessing (you can't forge a signature without the secret). But it's not enough on its own to stop a *legitimate, unmodified* URL being reused by the wrong person, or reused after the reason it was issued no longer holds. So the tile endpoint checks, in order — [`SecureTileService.java:61-121`](../../backend/src/main/java/com/secureleaf/viewer/service/SecureTileService.java#L61):

1. **JWT present** (401) — enforced by Spring Security before the controller even runs ([`SecurityConfig.java:72-73`](../../backend/src/main/java/com/secureleaf/auth/security/SecurityConfig.java#L72)). *Who are you, at all?*
2. **& 4. Signature valid, unexpired, and bound to *this* caller's user ID** (403) — the HMAC payload includes `userId`, so checking it with the caller's own JWT-derived ID does double duty: a tampered field fails, and so does a genuine URL presented by someone else's JWT, in the exact same check. *Is this URL real, unmodified, and actually yours?*
3. **Single-use** (403) — §3.3. *Has this exact permission slip already been spent?*
5. **The session is still active in Redis** (409/410) — §3.4. *Is the device that asked for this URL still the one currently reading this book?*
6. **The entitlement is still ACTIVE** (403) — re-checked fresh from the database on *every single tile*, not cached from when the session started. *Does this buyer still, right now, own this?*
7. **The page is in range for the entitled document version** (404). *Does this page even exist for what they bought?*

**Why each one matters even though the others exist:** drop check 6 and a refund mid-session keeps working for the rest of that 45-second lease. Drop check 5 and a superseded session's already-issued URLs (minted moments before takeover) keep serving tiles for their remaining 30 seconds. Drop checks 2/4 and a leaked URL becomes usable by literally anyone who finds it, not just its intended reader. No single check is redundant; each closes a window the others leave open.

**What breaks without this layering:** any *one* corner cut here turns into its own separate way to keep reading a book you shouldn't. Security bugs found in production are almost always "we only checked A, and forgot B was also required" — this is the direct countermeasure to that class of bug.

---

### 3.8 Canvas rendering instead of `<img>` (05B, D2)

**What it is:** the reader never creates an `<img>` element, a CSS `background-image`, or an `<img src="blob:...">` object URL for a page. Every page reaches the screen only as pixels already painted onto a `<canvas>` by JavaScript.

**The analogy:** an `<img src>` is a labelled parcel sitting on the counter — right-click it, and the browser offers to hand you the parcel itself ("Save image as…", drag it to the desktop, `document.querySelector('img').src` from DevTools). A `<canvas>` is a wet-paint mural on the wall: you can look at it, but there's no parcel to hand over — the browser's own "save this" affordances have nothing to attach to, because as far as the DOM is concerned a canvas is just colored pixels, not a fetchable resource.

**Why we needed it here:** Phase 3's free-preview `PreviewPane` already drew onto a canvas, but it got there via `new Image()` + `URL.createObjectURL(blob)` — an `<img>` element, just one that's never inserted into the DOM. That's fine for a few free pages nobody's paying for. 05B raises the bar for paid content: the spec (D2) is explicit that *nothing* in the pipeline may be an `<img>` or an object URL, full stop — see §3.9 for why object URLs specifically are worse than they look.

**How it works — the exact chain, [`useSecureTile.ts:99-126`](../../frontend/src/hooks/viewer/useSecureTile.ts#L99):**
1. `fetch(url, { cache: 'no-store', headers: accessToken ? { Authorization: ... } : {} })` — a normal authenticated fetch, not an `<img src>` (which can't carry an `Authorization` header at all — that alone would have forced a signed-URL-without-auth design, one more thing to get wrong).
2. `response.blob()` → raw bytes, still nowhere near the DOM.
3. `createImageBitmap(blob)` → decoded off-screen, in a form only `<canvas>` APIs understand (§3.9).
4. `ctx.drawImage(bitmap, 0, 0, bitmap.width, bitmap.height)` in [`drawBitmapToCanvas`](../../frontend/src/hooks/viewer/useSecureTile.ts#L39-L48) → pixels land directly in the canvas's backing buffer.

**In our code:** [`ViewerCanvas.tsx:22-29`](../../frontend/src/components/viewer/ViewerCanvas.tsx#L22) — the component that owns the one `<canvas>` element in the whole page; nothing else in `ReaderPage` ever touches an `<img>`.

**What breaks without it:** with an `<img src="blob:...">`, right-click → "Save image as…" gets you the exact, still-watermarked-but-otherwise-complete page, no DevTools required. 05A's entire signed-URL/single-use/watermark machinery would still be technically correct and still be trivially defeated by the browser's own built-in "save this picture" feature.

---

### 3.9 `createImageBitmap` and closing what you decode

**What it is:** `createImageBitmap()` is a browser API that decodes image bytes (PNG, JPEG, a `Blob`) into a GPU-friendly, DOM-detached `ImageBitmap` — fast to draw, and unlike an `<img>`, it never needs a URL (blob or otherwise) to exist at all. `.close()` releases the decoded bitmap's memory immediately, instead of waiting for garbage collection to eventually notice nothing references it anymore.

**The analogy:** a `blob:` object URL is a coat-check ticket — as long as it exists, someone holding the ticket number can walk up and claim the coat (the raw bytes), and forgetting to hand back the ticket (`URL.revokeObjectURL`) means the coat sits there taking up space, and *reachable*, indefinitely. `createImageBitmap` skips the coat-check entirely: you get a chef already wearing the coat backstage, with no ticket in existence for a stranger at the front desk to ever grab.

**Why we needed it here:** an object URL, even one nothing currently displays, is a live, guessable-format (`blob:https://.../uuid`) reference to the raw bytes for as long as the page lives and nobody calls `revokeObjectURL`. `createImageBitmap` never creates that reference in the first place — there's no URL scheme in existence that resolves to "the current contents of this specific decoded bitmap." Combined with §3.8's "never an `<img>`," this closes the object-URL loophole PreviewPane's Phase 3 approach still had.

**How it works — the memory-hygiene rule this hook follows, [`useSecureTile.ts:9,51-58`](../../frontend/src/hooks/viewer/useSecureTile.ts#L9):** a decoded `ImageBitmap` is native memory the JS garbage collector does **not** reliably reclaim promptly — it has to be told. So the rule is: the *currently displayed* page's bitmap is drawn and `.close()`d in the same tick (D2's flow: fetch → decode → draw → close — nothing needs it after the pixels are on screen). The *only* bitmaps allowed to live longer are prefetched-but-not-yet-shown pages, capped at `MAX_CACHED_BITMAPS = 3` with the oldest closed on eviction:
```ts
function evictOldest(cache: Map<number, ImageBitmap>, maxSize: number): void {
  while (cache.size > maxSize) {
    const oldestKey = cache.keys().next().value;
    if (oldestKey === undefined) break;
    cache.get(oldestKey)?.close();
    cache.delete(oldestKey);
  }
}
```
[`useSecureTile.ts:51-58`](../../frontend/src/hooks/viewer/useSecureTile.ts#L51)

**What breaks without it:** two different failure modes, not one. Forgetting `.close()` on the *drawn* bitmap leaks native decode memory on every single page turn — a long reading session slowly eats more and more RAM for pages already off-screen. Forgetting the cache *cap* on prefetched bitmaps turns "fast page turns" into "leak memory faster, because now you're holding several undrawn decoded pages at once instead of one."

---

### 3.10 `fetch(..., { keepalive: true })` vs. `navigator.sendBeacon` (05B, D3)

**What it is:** two different browser APIs for "send one last request as the page is closing, and don't let the browser cancel it just because the document is going away." They look interchangeable in every tutorial that doesn't need auth.

**The analogy:** `sendBeacon` is dropping a postcard in a mailbox on your way out the door — guaranteed to be sent, but a postcard has no security envelope, so it can't carry a sealed letter (a header) inside it. `fetch(..., { keepalive: true })` is handing that same envelope to a courier who's already leaving anyway — it still goes out reliably even though you're gone, but unlike the postcard it can carry an addressed, sealed envelope: an `Authorization` header.

**Why we needed it here:** ending a viewer session (`endViewerSession`) must be attributed to a specific buyer — the backend requires a valid JWT on every GraphQL mutation, no exceptions for "the tab is closing." `navigator.sendBeacon(url, body)` cannot attach custom headers *at all* — it exists specifically for simple, unauthenticated analytics pings, which is exactly the tutorial use case it's usually shown for and exactly the use case that doesn't apply here.

**How it works:** [`useViewerSession.ts:35-46`](../../frontend/src/hooks/viewer/useViewerSession.ts#L35)
```ts
function endSessionBestEffort(sessionToken: string): void {
  const accessToken = useAuthStore.getState().accessToken;
  void fetch(GRAPHQL_URL, {
    method: 'POST',
    keepalive: true,
    headers: {
      'Content-Type': 'application/json',
      ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}),
    },
    body: JSON.stringify({ query: END_VIEWER_SESSION_QUERY, variables: { sessionToken } }),
  }).catch(() => undefined);
}
```
This fires from a `pagehide` listener (fires on tab close, refresh, and back/forward navigation — unlike `beforeunload`, which is unreliable and increasingly restricted) *and* from the hook's own React unmount cleanup, so an in-app navigation away from `/read/:id` ends the session exactly the same way a hard tab close does — see [`useViewerSession.ts:134-143`](../../frontend/src/hooks/viewer/useViewerSession.ts#L134).

**What breaks without it:** using `sendBeacon` here would mean either sending the request with no `Authorization` header (the backend rejects it, 401, and the session silently never ends — leaving a "phantom" active session other devices have to wait out the 45-second lease to override) or inventing some other way to authenticate a beacon (a token in the URL query string, which then shows up in server access logs — trading one problem for a worse one).

---

### 3.11 Custom hooks as units of behaviour (05B, D7)

**What it is:** each piracy-friction control — block right-click, blur on focus loss, the DevTools heuristic, block print/save shortcuts, the PrintScreen blank — is its own tiny hook (`useBlockContextMenu`, `useBlurOnFocusLoss`, `useDevToolsHeuristic`, `useBlockPrintAndSaveShortcuts`, `usePrintScreenBlank`), each in its own file, each independently unit-tested, instead of one big `useViewerFriction()` grab-bag or inline `useEffect` blocks scattered through `ReaderPage`.

**The analogy:** five separate smoke detectors, one per room, each testable by holding a lighter under it — versus one central alarm box wired to sensors nobody can trigger without setting the actual kitchen on fire. Testing the five separate ones tells you precisely which room's detector is broken; testing the one big box only tells you "the house's fire safety is fine" until the day it very much isn't, in a way you can't localize.

**Why we needed it here:** every one of these controls has exactly one job, is genuinely independent of the others (turning off the DevTools heuristic shouldn't risk breaking the print-blocking shortcut), and — critically for a phase whose own spec says "be honest that these are all bypassable" (§3.13) — each needs a test that pins down *exactly* what it does and doesn't catch, in isolation, so that honesty is enforced by the test suite rather than by prose alone.

**How it works:** a hook in this file is judged by one question — "does it manage its own subscription lifecycle (attach a listener on mount, detach on unmount) and expose the smallest useful surface?" Compare two of them:
```ts
// useBlockContextMenu.ts — no side effects, no lifecycle, just a stable event handler:
export function useBlockContextMenu() {
  return useCallback((event: { preventDefault: () => void }) => {
    event.preventDefault();
  }, []);
}
```
```ts
// useDevToolsHeuristic.ts — owns a resize listener AND a poll interval, cleans both up:
useEffect(() => {
  const check = () => setDevToolsBlurred(devToolsLikelyOpen());

  check();
  window.addEventListener('resize', check);
  const intervalId = window.setInterval(check, POLL_INTERVAL_MS);

  return () => {
    window.removeEventListener('resize', check);
    window.clearInterval(intervalId);
  };
}, [setDevToolsBlurred]);
```
[`useBlockContextMenu.ts:9-13`](../../frontend/src/hooks/viewer/useBlockContextMenu.ts#L9), [`useDevToolsHeuristic.ts:23-34`](../../frontend/src/hooks/viewer/useDevToolsHeuristic.ts#L23)

**What breaks without it:** one monolithic effect block in `ReaderPage` mixing five unrelated concerns is exactly the shape of code where a merge conflict or a "quick fix" to one control silently breaks another, and where a test failure just says "something in the viewer is wrong" instead of naming which control regressed.

---

### 3.12 Apollo for remote state, Zustand for local state (05B, D8)

**What it is:** two different state-management tools, deliberately not interchangeable here. Apollo Client's cache holds data that *comes from the server* (the viewer session, a signed tile URL). Zustand's `useViewerStore` holds state that only ever exists *in this browser tab* and that the server has no concept of (which page is currently on screen, whether the canvas is blurred right now).

**The analogy:** Apollo's cache is a synced ledger with the bank — it can be wrong for a moment if the network is slow, but it's ultimately reconciling against a source of truth outside your control. Zustand's store is more like the position of your own cursor on a page — there's no "server" to ask, because the fact only exists locally, right now, in this tab.

**Why we needed it here:** `currentPage`, `focusBlurred`, and `devToolsBlurred` are never something a GraphQL query would return — no endpoint on earth can tell you "is *this specific browser tab* currently blurred because its window lost focus." Forcing that into Apollo's cache (e.g., a fake client-only field) would mean fighting the tool's actual job — normalizing and refetching *server* data — to store something that was never server data to begin with.

**How it works:** [`viewerStore.ts:10-32`](../../frontend/src/stores/viewerStore.ts#L10) is a plain Zustand store with no middleware — deliberately not `persist`-wrapped (unlike `authStore`, which *should* survive a refresh): a reader landing back on `/read/:id` after a refresh should re-derive its page from the URL's `?page=`, not from whatever was left over in a previous tab's local storage. Meanwhile `useViewerSession` and `useSecureTile` talk to Apollo exclusively — `useMutation`, `useApolloClient().query` — and never touch the Zustand store themselves; `ReaderPage` is the one place that reads from both and wires them together.

**What breaks without it:** blending the two — say, keeping `currentPage` in Apollo's cache via a client-only field, or keeping the *signed URL* in Zustand so it "survives longer" — either fights Apollo's refetch/normalization model for data it was never meant to hold, or defeats D5's single-use design by caching a URL that's only supposed to be used once and then thrown away.

---

### 3.13 The honest limits of browser DRM — what each control stops, and how it's beaten

**What it is:** the phase 05B spec is explicit that these controls "raise the effort for casual copying, and every one of them can be bypassed." This section is that promise kept: naming, for each control, exactly what stops and exactly how someone gets past it anyway.

**The analogy:** a bicycle lock. It stops someone from riding off on your bike by hand. It does not stop bolt cutters, and no bike-lock manufacturer honestly claims otherwise. The value of the lock is that it stops the *casual* theft — the person walking past who'd take an unlocked bike but won't fetch bolt cutters for a locked one.

**Why it matters (again):** §3.1 made this argument for the backend's watermark/signing story. The same honesty applies with even more force to browser-side controls, because every single one of them runs entirely inside an environment (the user's own browser) that the user fully controls. Overclaiming here is the fastest way to sound junior to anyone who has ever opened DevTools.

| Control | Requirement | Stops | Trivially beaten by |
|---|---|---|---|
| Canvas-only rendering | VIEW-01 | Right-click "Save image as…", drag-to-desktop, reading `<img src>` from the DOM | Screenshotting the rendered canvas; a browser extension that hooks `CanvasRenderingContext2D.drawImage` |
| Block context menu | VIEW-04 | The right-click menu itself (so no "Save image as…" entry ever appears) | Any other way to save a screenshot; the browser's own built-in screenshot shortcut, which no page-level JS can intercept |
| `select-none` / `draggable={false}` | VIEW-05, VIEW-06 | Selecting/copying text (there is none — it's an image) that doesn't exist; dragging the canvas out as a file | Nothing to select in the first place, so mostly stops confused clicking, not a real removal method — this row is more "there was never anything to protect" than "protection" |
| `@media print` + Ctrl/Cmd+P/S block | VIEW-07 | The print dialog rendering the page; the browser's native "Save Page As" shortcut | Any external screenshot tool never touches the keyboard shortcut at all; a browser that ignores the print stylesheet |
| Blur on focus loss | VIEW-09 | A page left visible and readable in a *background* window — an easy, lazy screenshot target | Screenshotting the *foreground*, focused window (the control's entire premise is "you're not looking at it right now") |
| DevTools-size heuristic | VIEW-08 | Casual DevTools use while the reader tab is focused (Elements panel poking at the canvas) | Undocked DevTools (a separate OS window — doesn't change `outerWidth`/`innerHeight` at all); some legitimate zoom levels also false-positive, which is exactly why it blurs instead of ending the session (§3.11's neighbor decision, and the code comment in [`useDevToolsHeuristic.ts`](../../frontend/src/hooks/viewer/useDevToolsHeuristic.ts)) |
| PrintScreen blank | VIEW-07 | The Windows "whole screen to clipboard" PrintScreen key, for the ~400ms window after it's pressed | The Snipping Tool, Cmd+Shift+4 on Mac, any phone camera, any third-party capture software — none of these are keyboard events this page can see at all |

**What breaks without naming this table plainly:** a design review — or a real interview — that hears "we block screenshots" instead of "we make a specific, named list of easy actions slightly harder, and we know exactly which ones we didn't touch" reads as either naive or dishonest. The backend's watermark (§3.1, §3.2) is the actual protection; everything in this table is friction on top of it, not a replacement for it.

---

### 3.14 Zoom: the canvas's CSS size vs. its backing store (addendum)

**What it is:** A `<canvas>` has two sizes. Its **backing store** (`canvas.width`/`height`) is how many real pixels it holds. Its **CSS size** is how big it is drawn on screen. The browser stretches one to fit the other.

**The analogy:** A printed photo (the backing store) viewed through a magnifying glass (the CSS size). The magnifier makes it bigger, but it can't add detail that isn't in the print.

**How it works here:** `useSecureTile` sets the backing store to the tile's resolution × `devicePixelRatio`, once per page. Zoom never touches it. `ViewerCanvas` only sets `canvas.style.width = fitWidth × zoom`, where `fitWidth = computeFitWidth(areaWidth, areaHeight, aspect)` (`frontend/src/lib/viewerLayout.ts`), the width at which the whole page fits. So:
- zooming is instant and **never re-fetches** a page (no new signed URL, access-log row or watermark render);
- the burned-in watermark scales with the page, so it can't be zoomed away;
- beyond the tile's native size the browser upscales, and text gets soft. The real fix is serving a higher-resolution tile variant, which is exactly Phase 16 (adaptive tile resolution).

**In our code:** `frontend/src/components/viewer/ViewerCanvas.tsx` (the `useLayoutEffect` that sizes the canvas). A `MutationObserver` on the canvas's `width`/`height` attributes re-fits when a differently-shaped page is drawn, and a `ResizeObserver` re-fits when the window changes.

**What breaks without it:** zooming by re-requesting bigger images would multiply server-side watermark work (the most expensive thing the backend does, per MVP2) for every zoom click.

### 3.15 Passive event listeners, and why Ctrl + wheel needs a native one

**What it is:** A *passive* listener promises the browser it will never call `preventDefault()`. That lets the browser scroll immediately without waiting for JavaScript. React attaches `onWheel` as passive, so calling `preventDefault()` inside it is silently ignored.

**Why we needed it:** browsers report both Ctrl + wheel and trackpad pinch as a `wheel` event with `ctrlKey: true`, and their default action is **zooming the whole tab**. To zoom only the page, `ViewerCanvas` adds a native `addEventListener('wheel', handler, { passive: false })` and cancels only when Ctrl/Cmd is held. A plain wheel still scrolls normally.

**Keyboard counterpart:** `useZoomShortcuts` also cancels Ctrl/Cmd + `+`/`-`/`0` inside the reader. Browser zoom changes `innerWidth` relative to `outerWidth`, which is exactly what the VIEW-08 DevTools heuristic measures, so leaving it alone could blur the page for no reason.

---

## 4. Best practices applied

| Practice | What we did | Why it matters | Where |
|---|---|---|---|
| Never presign protected content | Tile URLs point at our own signing controller, never at storage | The clean file is never one HTTP hop away from the browser | `TileUrlSigner.java`, `SecureTileController.java` |
| Right primitive per policy | `SET...GET` (last-writer-wins) for sessions, `SET NX` (first-writer-wins) for signatures | Using the wrong one either locks buyers out or lets URLs replay | `ViewerSessionService.java:59-66`, `SecureTileService.java:75` |
| Canvas backing store | The canvas's real pixel buffer (`canvas.width/height`), separate from its CSS display size |
| Fit width | The CSS width at which the whole page fits the reading area; the reader's "100%" zoom |
| Passive event listener | A listener that promises not to call `preventDefault()`, so the browser can scroll without waiting for it |
| Atomic compare-and-refresh | A Lua script, not `GET` then `EXPIRE` | Closes the race where another session's write lands between the two calls | `ViewerSessionService.java:68-79` |
| Constant-time signature compare | `MessageDigest.isEqual`, same as Phase 4's HMAC checks | Closes a timing side-channel | `TileUrlSigner.java:62` |
| Re-check authorization on every request | Entitlement status re-read fresh per tile, not cached on the session | A revoke takes effect on the very next tile, not at the next login | `SecureTileService.java:96-100` |
| Hash, don't store, secret tokens | Session token stored as `SHA-256(token)`; raw token returned once | A DB leak alone can't hand out working sessions (same as refresh tokens, Phase 1) | `ViewerSessionService.java:112`, reusing `JwtService.hashToken` |
| Fail fast on bad config | Refuses to start in `prod` with the dev-default signing secret | A forgotten env var becomes a 5-second local crash, not a live forgeable-URL bug | `DrmConfig.java:38-45` |
| Injectable Clock | `TileUrlSigner`/`DrmConfig` take a `Clock`, not `Instant.now()` | Expiry logic is unit-testable without sleeping or racing the real clock | `DrmConfig.java:44-47`, `TileUrlSignerTest.java` |
| Reuse, don't reinvent | `WatermarkRenderer`, `StorageService`, `hashToken`, `MessageDigest.isEqual` all reused unchanged from Phases 1/3/4 | Less new code to get wrong; one Strategy interface serves two phases | `SecureTileService.java` constructor |
| Never log on failure | Access-log write happens only after every D6 check passes | Failed attempts (wrong signature, expired lease, revoked access) don't pollute "who actually read this" | `SecureTileService.java:116-118` |
| GraphQL for data, REST for bytes | Session lifecycle is GraphQL; tile bytes are a REST `GET` | Matches CLAUDE.md's rule and keeps binary image responses out of the GraphQL layer | `ViewerResolver.java`, `SecureTileController.java` |
| **05B —** never let a page reach the DOM as `<img>` | `fetch` → `blob()` → `createImageBitmap()` → `drawImage()` | Removes "Save image as…" as a one-click affordance the browser offers for free | `useSecureTile.ts:99-126` |
| **05B —** close every `ImageBitmap` you're done with | Drawn bitmaps close immediately; the prefetch cache is capped at 3 and evicts-and-closes | Decoded bitmaps are native memory the GC doesn't reliably reclaim promptly | `useSecureTile.ts:39-58` |
| **05B —** `keepalive` fetch, not `sendBeacon`, for the one authenticated "goodbye" call | `fetch(url, { keepalive: true, headers: { Authorization } })` on `pagehide` + unmount | `sendBeacon` cannot carry custom headers; every viewer mutation requires a JWT | `useViewerSession.ts:35-46` |
| **05B —** never cache a signed URL, only a decoded bitmap | The tile-URL cache doesn't exist; only undrawn `ImageBitmap`s are kept, keyed by page | Signed URLs are single-use and expire in ~30s — caching one just means it's dead when you need it | `useSecureTile.ts:60-67` |
| **05B —** derive the fetched page number, never store an unclamped one | `clampedPage = clampPage(currentPage, pageCount)` computed every render, fed to every consumer | A stale `?page=` from a shorter edition (or one typed by hand) must never reach the tile fetcher, even for one render — see Gotcha #8 | `ReaderPage.tsx:47-51` |
| **05B —** an honest heuristic degrades gracefully, it doesn't escalate | DevTools-size heuristic blurs the page; it never ends the session | False positives are expected (§3.13) — a wrong guess should be *reversible*, not punitive | `useDevToolsHeuristic.ts` |
| **05B —** one small hook per independent behaviour | 5 friction hooks, each unit-tested alone | A regression in one shows up as one failing test, not "something in the viewer broke" | `hooks/viewer/*.ts` |

---

## 5. What does what — file map

| File | Responsibility |
|---|---|
| `db/migration/V5__viewer_sessions.sql` | Adds `viewer_sessions.end_reason` + CHECK constraint |
| `viewer/entity/ViewerSession.java`, `ViewerSessionEndReason.java` | The session row; why it ended |
| `viewer/entity/ViewerAccessLog.java` | One row per successful tile view |
| `viewer/repository/ViewerSessionRepository.java` | Lookup by token hash; the sweeper's "lapsed lease" query |
| `viewer/DrmProperties.java`, `DrmConfig.java` | `drm.*` config binding; prod fail-fast guard; the `Clock` bean |
| `viewer/security/TileUrlSigner.java` | Pure HMAC sign/verify for tile URLs (D5) |
| `viewer/service/ViewerSessionService.java` | start/heartbeat/end/supersede + the sweeper (D3, D4) |
| `viewer/service/SecureTileService.java` | The D6 ordered checks, then watermark + return bytes |
| `viewer/service/ViewerAccessLogService.java` | The D9 synchronous audit insert |
| `viewer/controller/SecureTileController.java` | `GET /api/viewer/tiles/{sessionId}/{pageNumber}` |
| `viewer/resolver/ViewerResolver.java` | GraphQL: `startViewerSession`, `viewerHeartbeat`, `endViewerSession`, `viewerPageUrl` |
| `common/exception/ErrorCode.java` | `NOT_ENTITLED`, `VIEWER_SESSION_SUPERSEDED`, `VIEWER_SESSION_EXPIRED`, `SIGNED_URL_INVALID` |
| `common/exception/GlobalRestExceptionHandler.java` | Special-cases the two viewer codes to 409/410 |
| `auth/security/SecurityConfig.java` | `/api/viewer/**` GET-only, authenticated, real 401 on no JWT |
| `resources/graphql/schema.graphqls` | `ViewerSession`, `ViewerHeartbeat`, `SignedPageUrl`, `ViewerSessionStatus` |
| `frontend/src/types/index.ts`, `graphql/{mutations,queries}/viewer.*.ts` | TypeScript types + documents (05A) — wired up by 05B below |
| **05B —** `frontend/src/pages/viewer/ReaderPage.tsx` | `/read/:productId` — orchestrates the session, the tile fetch, page nav, and every friction hook |
| **05B —** `frontend/src/hooks/viewer/useViewerSession.ts` | start/heartbeat/restart/end — the client half of D3/D4 |
| **05B —** `frontend/src/hooks/viewer/useSecureTile.ts` | Fetch → decode → draw → close, plus the 1-page-ahead prefetch cache |
| **05B —** `frontend/src/hooks/viewer/use{BlockContextMenu,BlurOnFocusLoss,DevToolsHeuristic,BlockPrintAndSaveShortcuts,PrintScreenBlank}.ts` | The five independent friction controls (D7) |
| **05B —** `frontend/src/stores/viewerStore.ts` | Zustand: `currentPage`, `focusBlurred`, `devToolsBlurred` (D8) |
| **05B —** `frontend/src/components/viewer/{ViewerCanvas,ViewerToolbar,SessionEndedPanel,NotEntitledPanel,ReaderSkeleton}.tsx` | The canvas + blur overlay, Prev/Next toolbar, and the three non-happy-path screens |
| **05B —** `frontend/src/lib/deviceFingerprint.ts` | A persisted per-browser id sent with `startViewerSession` |
| **05B —** `frontend/src/pages/buyer/LibraryPage.tsx`, `components/marketplace/BuyPanel.tsx` | "Read" / "Read now" now link to `/read/:id` instead of being disabled (D9) |

**Request trace — a buyer opens a book and turns one page (backend, 05A):**
1. `ViewerResolver.startViewerSession(productId, deviceFingerprint)` →
2. `ViewerSessionService.startViewerSession`: entitlement lookup (else `NOT_ENTITLED`) → generate + hash a 256-bit session token → save the row → `SET viewer:active:{userId}:{productId} {sessionId} EX 45 GET` → if a previous session comes back, mark it `SUPERSEDED` → return `{sessionId, sessionToken, pageCount, ...}` (the raw token, once) →
3. The client stores the token, starts heartbeating every 15s, and calls `viewerPageUrl(sessionToken, 1)` →
4. `ViewerResolver.viewerPageUrl` → `ViewerSessionService.signPageUrl`: look up the session by token hash, check it belongs to the caller, `TileUrlSigner.sign(sessionId, 1, userId)` → `{url, expiresAt}` →
5. The client `GET`s that URL with its normal JWT → `SecureTileController.getTile` → `SecureTileService.getTile`: verify signature (2+4) → single-use (3) → active session (5) → entitlement ACTIVE (6) → page in range (7) → fetch clean tile → watermark it → **one** `viewer_access_logs` row → `image/png`, `Cache-Control: no-store, private`.

**Request trace — the same page turn, from the browser's side (frontend, 05B):**
1. A buyer clicks "Read" in `LibraryPage` → `<Link to="/read/7">` → `ReaderPage` mounts inside `ProtectedRoute` →
2. `useViewerSession('7')` fires `startViewerSession` on mount → stores `{ sessionId, sessionToken, pageCount, heartbeatIntervalSeconds }` → `status` flips `'starting'` → `'active'` → an interval starts calling `viewerHeartbeat` every `heartbeatIntervalSeconds` →
3. `useSecureTile({ session, pageNumber: 1, ... })` fires: `apolloClient.query(VIEWER_PAGE_URL)` → gets back the signed `url` → `fetch(url, { headers: { Authorization }, cache: 'no-store' })` → `blob()` → `createImageBitmap()` →
4. `ctx.drawImage(bitmap, 0, 0, ...)` onto the one `<canvas>` in `ViewerCanvas` → `bitmap.close()` → the buyer sees page 1 →
5. In the background, the *same* hook prefetches page 2 the same way, but stops after step 3 — the decoded bitmap sits in a `Map` cache instead of being drawn, until the buyer actually turns the page →
6. The buyer clicks "Next" (or presses →) → `ReaderPage.goToPage(2)` → `?page=2` in the URL, `currentPage` in the Zustand store both update → `useSecureTile` re-runs, finds page 2 already decoded in its cache, draws it immediately (no network round trip — this is the whole point of the prefetch), and closes it →
7. On `pagehide` or unmounting (closing the tab, or navigating back to the library), `fetch('/graphql', { keepalive: true, headers: { Authorization }, body: '{ endViewerSession(...) }' })` fires — the session is over on the server, not just in this tab.

---

## 6. Design decisions and trade-offs

### Decision: bake `userId` into the HMAC payload instead of adding it as a URL parameter
- **Alternatives considered:** add `&userId=123` to the signed URL and compare it against the caller's JWT as a separate check.
- **Why we chose this:** the spec's D6 lists "signature valid" and "JWT user equals signed userId" as two separate checks, but there's no separate `userId` field to compare — it's one of the four fields the signature is computed *over*. Recomputing the HMAC with the caller's own JWT-derived user ID means a URL signed for user A simply fails to verify for user B, in the same call that catches tampering. One check does the work of two, and the buyer's numeric ID never appears in a URL that might end up in server logs or a browser history.
- **What we gave up:** the two checks are no longer independently observable in code — you can't unit-test "signature is well-formed but wrong user" as a distinct code path, because there isn't one. Documented in `SecureTileService`'s comment so a future reader doesn't go looking for a second check that was never meant to exist.
- **When we would revisit:** never, unless the signing scheme itself changes (e.g. moving to JWT-shaped tile tokens with their own claims).

### Decision: Redis, not the database, for the active-session pointer
- **Alternatives considered:** a `viewer_sessions.is_active` boolean flag with a partial unique index, like Phase 4's `uq_entitlements_buyer_product_active`.
- **Why we chose this:** the active-session pointer needs a **TTL that decays on its own** if nobody renews it — that's exactly what Redis `EX` gives for free. A database flag would need the sweeper to be the *only* mechanism keeping "is this session still alive" honest, checked on every single heartbeat and tile fetch — turning a fast, in-memory check into a database round trip on the hottest path in the system (every 15 seconds, per open tab). Postgres stays the durable, queryable history (every session ever); Redis is the fast, self-expiring answer to "who owns this slot right now".
- **What we gave up:** two sources of truth that must agree. A session's DB row and its Redis key can, briefly, disagree (see §3.4's explanation of why `heartbeat()` doesn't write `ended_at` itself). `SecureTileService.inactiveReason` therefore has to check *both* — DB first (cheap, and authoritative once set), Redis second (the live check) — rather than trusting either alone.
- **When we would revisit:** if Redis itself became a reliability concern (single point of failure for reading anything at all) — the fallback would be a database-only design with a much more aggressive sweeper interval, accepting the extra query load.

### Decision: `viewer_access_logs` is append-only by convention, not by a database trigger
- **Alternatives considered:** copy Phase 4's `forbid_payment_event_mutation()` trigger onto this table too.
- **Why we chose this:** `payment_events` protects money — the cost of it ever being editable is a dispute nobody can win. `viewer_access_logs` protects a "who looked at what" trail that matters for tracing a leak, not for settling a financial dispute. Adding the same hard guarantee to every append-only-shaped table in the codebase, whether or not its consequences justify it, is exactly the kind of over-applied pattern CLAUDE.md's "no half-finished implementations" rule warns against in the other direction — copying a heavier guarantee than the requirement calls for isn't free either; it's one more migration, one more thing that can reject a legitimate future need (like a GDPR erasure request against `viewer_access_logs.ip_address`).
- **What we gave up:** a compromised or buggy future service *could* edit this table's history, in a way it provably cannot for `payment_events`.
- **When we would revisit:** the moment this table needs to survive a dispute or a legal request the way `payment_events` does — trivial to add later (it's the same three-line trigger function, reused).

### Decision: a synchronous access-log write, not an outbox or async publish
- **Alternatives considered:** Phase 4's `AFTER_COMMIT` + `@Async` pattern; a full Transactional Outbox.
- **Why we chose this:** see §3.6 — there's no "don't tell anyone about a purchase that rolled back" danger here to defer around, and no slow external system (SMTP) in the write path to protect the request from.
- **What we gave up:** one extra synchronous `INSERT` on the hottest read path in the system (every page turn). At MVP traffic this is noise next to watermark rendering (Java2D, per request, uncached).
- **When we would revisit:** the moment access-log writes show up as measurable tail latency under real load, or logging needs to survive the tile service itself being unavailable.

### Decision: a tiled/repeated watermark pattern is explicitly out of scope
- **Alternatives considered:** building a repeated, tiled watermark (like Google Docs' "CONFIDENTIAL" diagonal grid) instead of reusing `WatermarkRenderer`'s existing single diagonal label.
- **Why we chose this:** `WatermarkRenderer` already exists from Phase 3 and this phase's D7 explicitly says reuse it unchanged, just with a different (buyer-identifying) label. A single, well-placed diagonal label is easy for a screenshot to accidentally crop out; a tiled pattern is much harder to crop away and is the more common real-world choice (see the PDF-fixture-size bug in §8 — the watermark's own grid math is size-dependent).
- **What we gave up:** a determined leaker could, in principle, screenshot a region that avoids the single label, or crop it afterward.
- **When we would revisit:** flagged in the phase spec as a named follow-up — this is the first thing I'd build next for this feature specifically.

### Decision (05B): re-fetch a page after it's viewed instead of keeping it around
- **Alternatives considered:** an LRU cache that also keeps *already-drawn* pages' bitmaps around, so paging back doesn't cost a network round trip either.
- **Why we chose this:** D5 says signed URLs are single-use and 30-second-lived, so there is nothing to gain from caching the *URL*. Once a bitmap has been drawn, its pixels are already on screen — keeping the decoded bitmap alive after that buys nothing except memory pressure, since re-fetching decodes it fresh in well under the time it takes a person to notice. Only *not-yet-viewed* pages (the one-ahead prefetch) are worth keeping decoded but undrawn.
- **What we gave up:** paging backwards costs one extra network round trip + decode instead of being instant, unlike paging forwards into an already-prefetched page. Measured against a real PDF, that's imperceptible; it would matter more for a "flip back and forth rapidly" reading pattern this phase doesn't optimize for.
- **When we would revisit:** if user analytics showed frequent, fast back-and-forth paging (comparing two pages), a small keep-last-N-drawn-bitmaps cache would be a cheap addition to the existing `Map`-based structure.

### Decision (05B): the DevTools heuristic blurs; it never ends the session
- **Alternatives considered:** treating a detected-DevTools state the same as VIEW-10's takeover — ending the session outright, forcing a restart.
- **Why we chose this:** §3.13 names this heuristic's real failure mode — it both under-detects (undocked DevTools) and over-detects (some zoom levels). An action with real consequences (ending a paid reading session) built on top of a heuristic that's *known* to sometimes be wrong is a worse trade than a control that's easy to reverse. Blurring is annoying but recoverable the instant the heuristic clears; ending a session is not something a false positive should ever be allowed to do.
- **What we gave up:** a determined user with DevTools genuinely open just sees a blurred page and closes DevTools, then keeps reading — this control adds friction, not enforcement, exactly as advertised.
- **When we would revisit:** never for this heuristic specifically; a *real* DevTools detector (there isn't a reliable cross-browser one) might change the calculus, but heuristic-based punishment is the wrong pattern regardless of which heuristic.

### Decision (05B): five small hooks, not one `useViewerFriction()`
- **Alternatives considered:** a single hook bundling all D7 controls, called once from `ReaderPage` with one big returned object.
- **Why we chose this:** see §3.11 — independent behaviors, independent tests, independent failure blast radius. The spec explicitly calls for this shape ("each in its own small hook... so it can be tested").
- **What we gave up:** slightly more import boilerplate at the call site (five `use...()` calls in `ReaderPage` instead of one) — a small, one-time cost paid once per page that uses them.
- **When we would revisit:** if a sixth, seventh friction control started sharing meaningful state with an existing one (not the case today — every hook's internal state is genuinely private), a shared hook might start pulling its weight.

---

### Decision (zoom addendum): scale the canvas in CSS, don't fetch bigger tiles
- **Alternatives considered:** request a higher-resolution tile per zoom level; re-render the PDF page in the browser (would require shipping the PDF, which violates VIEW-01).
- **Why we chose this:** zero extra server work, no new attack surface, instant response, and the watermark scales with the content.
- **What we gave up:** sharpness above roughly 150–200%, depending on the tile's native width.
- **When we would revisit:** Phase 16 (adaptive tile resolution): request a larger variant once zoom × fit width exceeds the tile's width.

### Decision (zoom addendum): swallow the browser's own zoom inside the reader
- **Alternatives considered:** leave Ctrl +/− alone and let users rely on browser zoom.
- **Why we chose this:** browser zoom doesn't enlarge a fit-to-screen canvas, and it can falsely trip the DevTools blur. Cancelling it inside `/read/*` only (the listeners are removed on unmount) keeps the rest of the site normal.
- **What we gave up:** inside the reader, Ctrl +/− no longer zooms the toolbar text itself.

---

## 7. Interview questions

> Answer out loud first. Then read. Ordered easy → hard.

### Beginner

**Q: What does DRM actually stop, and what can't it stop?**
A: It stops casual, low-effort copying and sharing — right-click-save, a permanent public link, watching from ten devices on one login. It can't stop someone determined enough to point a camera at their screen and photograph every page; nothing server-side can. What it adds on top is traceability: every page that reaches a browser is watermarked with the buyer's identity, so a leak can be traced back to whoever's copy it came from.

**Q: Why does a page URL expire after 30 seconds?**
A: So that even if the URL leaks — pasted somewhere, logged by a proxy, captured by a browser extension — it's only useful for a brief window. Combined with being single-use, a captured URL is worthless almost immediately either way.

**Q: What is a watermark, in this system?**
A: Text burned directly into the page image's pixels before it's sent to the browser — the buyer's email, their user ID, a timestamp, and the session ID. It's not metadata that can be stripped; it's part of the picture itself.

**Q: Why is the tile endpoint REST, not GraphQL, if session management is GraphQL?**
A: CLAUDE.md's rule: GraphQL for data, REST for files. A GraphQL response is JSON; putting binary image bytes through it means base64-encoding them, which is bigger and slower for no benefit. A plain `GET` returning `image/png` is what browsers, caches, and `<canvas>`-loading code already expect.

**Q: What happens if a buyer opens the viewer on a second laptop?**
A: The second `startViewerSession` call takes over — it becomes the one active session for that buyer and that product, and the first one is marked superseded. The first laptop's next heartbeat reports `SUPERSEDED`, and its viewer should stop letting them read further.

### Intermediate

**Q: Walk me through what happens, step by step, when a signed tile URL is fetched.**
A: First, Spring Security checks there's a valid JWT at all — no token is a 401 before my code even runs. Then the tile service verifies the HMAC signature against the caller's own user ID, which also proves the URL wasn't tampered with and was actually issued to this person — one check does both, because the user ID is baked into what's signed. Then it checks the signature hasn't been used before, with a Redis `SET NX`. Then it checks the session is still the active one for that buyer and product, not superseded or expired. Then it re-reads the entitlement fresh from the database — not cached from when the session started — to catch a mid-session revoke. Then it checks the page number is in range for the document version this buyer actually owns. Only after all of that does it load the clean tile, burn the watermark in, log the access, and return the bytes.

**Q: Why does the signature check double as the "is this your URL" check, instead of being two separate checks?**
A: The HMAC is computed over sessionId, pageNumber, the caller's userId, and the expiry — all four fields, together. When I verify it, I recompute that same HMAC using whoever's JWT is making the request right now. If someone else's JWT tries to use a URL signed for a different user, the recomputed signature simply won't match, because the userId that went into the original signature is different from theirs. So "is the signature valid" and "is this signature yours" collapse into the exact same comparison — I don't need a separate userId field in the URL to check against, which also means a buyer's numeric ID never shows up in a URL that could end up in a log file.

**Q: Explain `SET key value EX 45 GET` and why it's atomic.**
A: It's a single Redis command that sets a key's value and expiry, and in the same round trip returns whatever value the key held immediately before. Because Redis processes one command at a time, there's no gap between "read the old value" and "write the new one" for another client to land in the middle of — which is exactly what you'd get if you did a plain GET followed by a separate SET from application code.

**Q: Why not just use `SET NX` for the session pointer too, so it's simpler?**
A: `NX` means "only set if nobody's there" — first writer wins. For the tile signature, that's exactly right: whoever presents it first is legitimate, everyone after is a replay. But for the session pointer, the *newer* login is supposed to win, not be rejected — a buyer opening the book on a second device should always be able to take over, not get told the first device already has it. Using `NX` there would permanently lock a buyer out of their own second device the first time they logged in from two places.

**Q: Why do you re-check the entitlement on every single tile fetch instead of once, when the session starts?**
A: Because access can be revoked at any point during a session — a refund, an admin action — and the whole point of checking is to make revocation actually take effect, not just look like it does. If I only checked at session start, a revoked buyer could keep reading for the rest of their 45-second lease, or longer if they kept heartbeating. Re-reading the entitlement fresh on every tile means a revoke is effective on literally the next page turn.

**Q: The heartbeat endpoint reports `EXPIRED` but doesn't update the database. Why not?**
A: Because ending a session — writing `ended_at` and the reason — should only happen in exactly one place per reason, so there's never a question of two code paths racing to close the same row differently. `SUPERSEDED` is written once, by `startViewerSession`'s takeover logic, at the moment it happens. `EXPIRED` is written once, by the scheduled sweeper, on its own timer. `heartbeat()` is read-mostly: it tells the caller the truth about what Redis currently says, but leaves the actual "close this row" job to whichever of those two is responsible for that specific reason.

**Q: Why is the sweeper needed if heartbeat already detects expiry?**
A: Heartbeat only detects expiry if the client calls it — but a buyer who just closes their laptop lid never calls anything again. Nothing tells the server that session died. The sweeper is the safety net: every 60 seconds it looks for any session whose last heartbeat is older than the lease and closes it, regardless of whether the client ever comes back to ask.

**Q: Where does the watermark's diagonal text placement actually get drawn from, and why did a 10x10 test image fail to prove it worked?**
A: `Java2DWatermarkRenderer` draws its repeating label on a grid sized off the image's own width and height — the step between repeats is roughly half the image size plus some margin based on the font. On a tiny image, that step is bigger than the whole canvas, so the single draw call can land entirely outside the visible pixels and the "watermarked" output comes out byte-identical to the clean input. I hit exactly that with a 10x10 test fixture; bumping it to 200x200 made the watermark reliably land on the canvas regardless of exact font metrics.

### Advanced / follow-up probes

**Q: Two devices both call `startViewerSession` for the same buyer and product within a few milliseconds of each other. Walk me through exactly what happens.**
A: Both requests insert their own `viewer_sessions` row — inserts don't conflict with each other. Then both run `SET viewer:active:{userId}:{productId} {sessionId} EX 45 GET` against the same Redis key. Redis processes commands one at a time, so whichever request's SET actually executes second wins the key and gets the first request's session ID back as the "previous" value; the loser's session gets marked SUPERSEDED by whichever request lost the race, even though from the outside it looked like they fired "simultaneously". There's a small, deliberately accepted window here: if the loser's own `startViewerSession` call hasn't yet reached the point of marking its own row SUPERSEDED when the winner's client tries a tile fetch, that fetch still correctly 409s, because `SecureTileService` also does a live Redis check, not just a DB check — so the outcome is correct even if the bookkeeping lags by a moment.

**Q: What's the actual attack this whole D6 ordering is designed to defeat, that a naive "just check the JWT" implementation wouldn't?**
A: A single check only closes one door. If I only checked the JWT, anyone logged in at all could hit any tile URL for anyone's session. If I only checked the signature, a leaked-but-valid URL from a session that's since been superseded, or an entitlement that's since been revoked, would keep working until it expired. Each of D6's checks closes a specific window the others leave open: identity, authenticity-and-ownership, replay, currency of the session, currency of the entitlement, and range. An attacker has to defeat all six simultaneously, not find whichever one was weakest.

**Q: You chose Redis for the "who's currently active" pointer instead of a database flag. What's the actual failure mode if Redis goes down mid-session?**
A: Every heartbeat and every tile fetch does a Redis read as part of `inactiveReason()`. If Redis is unreachable, those calls fail — which, as written, would surface as an error rather than silently granting access, so the system fails closed, not open. The practical cost is that active viewer sessions become unusable for the outage's duration, which is a real availability trade-off I accepted for the speed and self-expiry Redis gives on the hottest path. A more resilient version would define an explicit fallback (e.g. treat a Redis timeout as "assume active, re-verify against Postgres"), which I didn't build here — see section 10.

**Q: How would you extend this to detect a screenshot-sharing ring, beyond what a single watermark tells you?**
A: The watermark alone tells you which buyer a specific leaked image came from, if you ever see that image. `viewer_access_logs` gives you more: IP address and user agent per page view, which lets you spot patterns a single watermark can't — the same buyer's account being used from IP ranges on opposite sides of the world within an hour, or one account generating far more page-view rows than a normal reading pace would produce. That's an anomaly-detection job running against this table, not a change to the viewer itself.

### "Tell me about a bug you fixed"

**Q: Tell me about a bug you found in this phase.**
A: While writing the integration test for "the watermark actually changes the bytes", I used the same tiny 10x10 test image Phase 3's equivalent test used, and it failed — the "watermarked" output was byte-for-byte identical to the clean input. I traced it to `Java2DWatermarkRenderer`: it draws its repeating label on a grid whose spacing is derived from the image's own width and height, so on an image far smaller than one grid cell, the single draw call can land entirely off-canvas and the watermark silently does nothing. It turned out Phase 3's own test had the exact same latent bug — it had just never actually been run, because of the Failsafe gap below, so nobody had seen it fail yet. I fixed both test fixtures to use a 200x200 canvas, large enough that at least one repeat of the grid always overlaps the visible image regardless of exact font metrics.

**Q: Tell me about a bug your tests caught that wasn't really about your own code.**
A: When I ran `mvn verify` the way the project's own definition of done requires, it reported success — but only 11 tests ran, all from one unit test class I'd just written. Every `*IT.java` integration test in the entire project, including every acceptance-criteria test from phases 1 through 4, silently never executed, because the POM had no Failsafe plugin bound to any phase, and Surefire's default file pattern only matches `*Test.java`. I added the Failsafe plugin, bound to `integration-test` and `verify`, and reran — which is when three pre-existing, unrelated bugs surfaced for the first time: a test calling `entityManager.flush()` outside any transaction, a JDBC call passing a bind parameter to a query with no placeholders for it, and an async pipeline test asserting a result immediately instead of waiting for the background thread that produces it. None of those were caused by my phase, but they were blocking the exact command my phase's definition of done required to pass, so I fixed all three. The lesson: a test suite that reports green tells you nothing if you haven't confirmed it can also report red — "have I actually seen this fail" is a question worth asking about the harness itself, not just the code it's supposedly checking.

---

### 05B — Beginner

**Q: Why does the viewer use `<canvas>` instead of just an `<img>` pointed at the tile URL?**
A: An `<img src>` is a real, fetchable resource the browser knows how to hand back to the user — right-click → "Save image as…", drag it to the desktop, even just reading `.src` out of DevTools. A `<canvas>` is just painted pixels; there's no URL, no file, nothing for those browser features to attach to. It's not unbreakable — you can still screenshot a canvas — but it removes every one-click way to save the page as a file.

**Q: What does `createImageBitmap` actually do, and why not just use `new Image()`?**
A: `createImageBitmap` decodes image bytes straight into a GPU-friendly bitmap without ever creating a DOM element or a URL. `new Image()` needs an `src` — usually a `blob:` object URL — which is itself a live, if obscure, reference to the raw bytes for as long as it exists. Skipping that entirely means there's no URL of any kind pointing at a clean page, even one nothing currently displays.

**Q: The spec says these browser controls "can all be bypassed." So what's the point?**
A: They raise the effort for casual copying — the person who'd right-click and save an image but won't go find a screen-capture tool for one book page. The actual protection is server-side: the watermark. These controls are friction on top of that, not a replacement for it, and pretending otherwise is the kind of overclaim that doesn't survive five minutes of a real interview.

### 05B — Intermediate

**Q: Walk me through what happens when a buyer clicks "Next" and the page is already prefetched.**
A: `goToPage` clamps the target page number and writes it to both the URL's `?page=` and the Zustand store. `useSecureTile`'s effect re-runs because `pageNumber` changed, checks its cache `Map` first, finds the bitmap already sitting there from the prefetch that ran after the previous page drew, and draws it immediately — no network call, no `viewerPageUrl` query, no `fetch`. Then it kicks off prefetching *the next* page after that, the same way. The only time a page turn costs a round trip is the very first page, or paging backward into a page that's already been drawn and closed.

**Q: Why can't you just use `navigator.sendBeacon` to end the session when the tab closes?**
A: `sendBeacon` can't attach custom headers, and ending a session is a GraphQL mutation that requires a JWT in the `Authorization` header like every other authenticated request — there's no anonymous way to do it. `fetch(url, { keepalive: true, headers: {...} })` gives the same "still sends even though the page is going away" guarantee sendBeacon is known for, but as a normal fetch it can carry whatever headers the request actually needs.

**Q: Why is `currentPage` clamped freshly on every render instead of just once when the session starts?**
A: Because `pageCount` isn't known until the session resolves, but the store's `currentPage` can already hold a value from the URL before that — including an out-of-range one, like `?page=99` on a 3-page book. If I clamped it with a `useEffect` that runs *after* `pageCount` arrives, there's a render in between where the raw, unclamped number is what gets handed to the tile fetcher — which would try to fetch page 99. Computing `clampedPage = clampPage(currentPage, pageCount)` directly in the render body, and feeding that everywhere instead of the raw store value, means there's no render where an out-of-range page can leak through — see the bug I hit doing this the other way, below.

### 05B — Advanced / follow-up probes

**Q: You cap the prefetch cache at 3 bitmaps. What's the actual failure mode if you didn't?**
A: Every `ImageBitmap` you don't `.close()` holds onto decoded, GPU-adjacent memory that the JS garbage collector won't necessarily reclaim promptly just because nothing references it — it's more like a manually-managed resource than a plain JS object. If a buyer mashes "Next" faster than pages can be drawn, an uncapped cache would just keep accumulating undrawn, un-closed bitmaps for every page the prefetch ever got ahead on, one per page-number key, forever. Capping it at 3 and closing whatever gets evicted bounds the worst case to "a handful of decoded pages," regardless of how fast someone pages through the book.

**Q: The DevTools heuristic can false-positive on legitimate zoom levels. Why is "blur, don't end the session" the right response to a heuristic you don't fully trust?**
A: Because the two possible mistakes have very different costs. If the heuristic wrongly thinks DevTools is open and blurs the page, the buyer sees a moment of blur and it clears itself the instant the dimensions look normal again — mildly annoying, fully recoverable, no data lost. If it wrongly ended the session instead, a buyer who just has an unusual zoom level gets kicked out of a book they're paying to read, based on a check the code's own comment admits is a guess. An action's reversibility should scale with how much you trust the signal that triggers it, and "blur" is the most reversible action available.

**Q: Why does the "end viewer session" fetch fire from both `pagehide` and the hook's unmount cleanup, instead of just one of them?**
A: They cover different exits. `pagehide` fires when the whole tab goes away — closed, refreshed, or navigated to a different site — none of which give React a chance to run its normal unmount cleanup, because the JS execution context itself is being torn down. The `useEffect` cleanup function fires for an *in-app* navigation — clicking "Exit" to go back to `/product/:id` while the SPA stays alive — which never triggers `pagehide` at all, since the tab and its `window` object never actually unload. Relying on only one would leave the other kind of exit never ending the session, silently holding the "active" slot until the 45-second lease lapses on its own.

---

### Zoom addendum — questions

**Q (Beginner): How does the reader zoom without downloading a bigger image?**
A: A canvas has a pixel buffer and a display size. We keep the pixels the same and only change the CSS width to "fit width × zoom". The browser stretches it. It's instant and costs the server nothing, but past the image's real resolution it gets a bit blurry.

**Q (Intermediate): Why couldn't you just call `preventDefault()` in React's `onWheel` to stop the browser zooming?**
A: React registers wheel listeners as passive for scroll performance, and a passive listener's `preventDefault()` is ignored. You need a native listener with `{ passive: false }`. And you should only cancel when Ctrl or Cmd is held, so normal scrolling stays fast.

**Q (Advanced): A zoomed page centred with `justify-content: center` loses its top-left corner. Why, and what's the fix?**
A: Flex centring distributes negative free space equally on both sides, so an overflowing child spills past the start edge, where the scroll container can't reach. `margin: auto` on the child centres it while it fits, and collapses to 0 once it overflows, so all of it stays scrollable. That's why the canvas uses `m-auto` rather than `items-center justify-center`.

---

## 8. Gotchas and bugs we hit

| # | Symptom | Root cause | Fix | Lesson |
|---|---|---|---|---|
| 1 | `viewerPageUrl`'s first draft threw `LazyInitializationException` when read from the resolver | `ViewerSessionRepository.findBySessionTokenHash` was called directly from the (non-transactional) GraphQL resolver; `session.getUser()` is a LAZY proxy that needs an open Hibernate session to resolve | Moved the lookup + ownership check + signing into a `@Transactional(readOnly = true)` service method (`ViewerSessionService.signPageUrl`) instead of touching the repository straight from the resolver | Lazy associations are only safe to touch inside the transaction that loaded them — keep entity access inside `@Transactional` service methods, not resolvers/controllers |
| 2 | `previewBytes_differFromStoredCleanTile...` (Phase 3) and the equivalent new viewer test both failed: "watermarked" bytes identical to clean bytes | `Java2DWatermarkRenderer`'s repeat grid is sized off the image dimensions; a 10x10 test fixture is smaller than one grid cell, so the single draw call can land entirely off-canvas | Test fixtures bumped to 200x200, large enough that a repeat always overlaps the canvas regardless of font metrics | A test's fixture size can silently make the thing it claims to prove never actually happen |
| 3 | `mvn verify` reported BUILD SUCCESS having run only 11 tests | No `maven-failsafe-plugin` execution bound to any phase; Surefire's default include pattern never matches `*IT.java` — every integration test in the project (all phases) was silently skipped in CI | Bound Failsafe to `integration-test` + `verify` in `pom.xml` | A passing build only means what you've confirmed it actually checks — see it fail once before trusting it |
| 4 | (surfaced by fixing #3) `MarketplaceQueryIT.search_usesTheFtsIndex` threw `TransactionRequiredException` on `entityManager.flush()` | Called outside any transaction; the test's own `makeProduct()` helper already calls `saveAndFlush()` per row, so the line was dead code that had simply never run before | Removed the redundant `flush()` call | Dead code doesn't get caught by "the tests pass" if the tests never ran it |
| 5 | (surfaced by fixing #4) The same test then threw `BadSqlGrammar` on its `EXPLAIN ANALYZE` query | `jdbcTemplate.queryForList(sql, Map.of())` — the query has zero `?` placeholders, so passing one bind argument is a parameter-count mismatch, not "no parameters" | Removed the extra argument; `queryForList(sql)` | An empty collection passed as a vararg isn't "nothing" — it's still one argument |
| 6 | (surfaced by fixing #5) The test's final assertion — "the plan uses `idx_products_fts`" — failed even with correct SQL | Every seeded row in the test matches *both* the status filter and the search term, so the FTS predicate has ~0% selectivity; Postgres correctly prefers the `(status, created_at)` index, which also satisfies `ORDER BY`/`LIMIT` for free, over the GIN index | Narrowed the assertion to what the test's own comment already promised — no seq scan — rather than one specific index name | A cost-based planner's choice depends on the data you actually seeded, not on which index you hoped it would pick |
| 7 | `ProcessingPipelineIT.processAsync_success` (Phase 2, surfaced by fixing #3) asserted `COMPLETED` immediately after calling the pipeline and got `PROCESSING` | `processAsync` is `@Async`; the call returns immediately and the real work happens on a background thread the test didn't wait for | Wrapped the assertion in `Awaitility.await().untilAsserted(...)`, the same pattern already used in `NotificationIT` | Calling an `@Async` method and asserting its result on the next line is a race, not a test |
| 8 | (05B) The pagination test loading `?page=99` on a 3-page book briefly requested `viewerPageUrl(sessionToken, 99)` — a mock-configuration error surfaced it, but the same thing would have hit the real backend | `ReaderPage` clamped `currentPage` in a `useEffect` keyed on `pageCount`, which only becomes known *after* the session starts. Between the render where the session activates and the render where that effect runs, `useSecureTile` briefly saw the raw, unclamped page number | Replaced the two-effect clamp with a single derived value — `const clampedPage = clampPage(currentPage, pageCount)` computed in the render body — and fed *that* to the toolbar, the tile fetcher, and page-navigation math, so there is no render where an out-of-range number exists | A value that's "eventually correct once an effect runs" is wrong for exactly as many renders as the effect takes to fire — if something downstream fetches on every render, derive it synchronously instead |
| 9 | (05B) Every heartbeat tick and every prefetch query printed a `MockedProvider` deprecation warning to the test output, even though the tests all passed | I'd used `MockedResponse`'s `newData` callback to let one mock answer a repeated query (heartbeat fires every few ms in one test). `newData` is deprecated in this Apollo Client version and unconditionally logs a warning on every use, regardless of whether the mock array itself was configured correctly | Switched to `result: () => ({...})` — a plain function, which Apollo Client also supports for a dynamic response, with `maxUsageCount: Infinity` so the same mock keeps matching — with no deprecation warning | A deprecation warning firing in a passing test suite is still worth chasing down; "the tests are green" and "the tests are clean" are different bars, and the second one is what stops real bugs from hiding in a wall of expected-looking noise |
| 10 | (05B) `MockedProvider`'s default `maxUsageCount` is 1 — a query mocked once and legitimately fetched twice (e.g. paging forward then back to a page whose prefetched bitmap was already drawn-and-closed) failed its *second* fetch with "No more mocked responses," not because of a code bug but because the test's own mock array only anticipated one call | Traced by reading `mockLink.js` directly rather than guessing from the warning text, which doesn't mention `maxUsageCount` at all | Set `maxUsageCount: Number.POSITIVE_INFINITY` on any `MockedResponse` a test expects to satisfy more than once | A test double's default limits (a mock's use-once default, here) are part of the contract you're testing against just as much as the code under test — when a test fails in a way the application code can't explain, check the test infrastructure's own defaults next |

| 11 | (zoom addendum) Browser zoom (Ctrl +) didn't enlarge the page at all | The canvas was `max-h-full max-w-full` inside an `overflow-hidden` box, so it always re-fitted whatever viewport it had | Explicit CSS width = fit width × zoom, inside an `overflow-auto` scroller | "Fit to container" and "zoomable" pull in opposite directions; pick one explicit size source |
| 12 | (zoom addendum) Zoomed page couldn't be scrolled to its top/left edge (a known CSS trap, avoided by design) | Flex `items-center justify-center` centres an overflowing child by pushing it past the start edge | `m-auto` on the canvas | Use `margin: auto` for "centre while it fits, scroll when it doesn't" |
| 13 | (zoom addendum) New sizing test saw `1000px` instead of `560px` | `MutationObserver` callbacks are delivered on a later microtask; a synchronous `act()` asserted before the observer had fired, while the canvas still had its default 300×150 shape | `await act(async () => …)` so pending microtasks run first | Observers are asynchronous; tests have to wait for them like any other async work |
| 14 | (zoom addendum) `npm run lint` failed on a warning | `react-refresh/only-export-components`: a component file also exported a helper function, which breaks hot reload; lint runs with `--max-warnings 0` | Moved `computeFitWidth` to `src/lib/viewerLayout.ts` | Pure helpers belong in `lib/`, not in component files |
---

## 9. New vocabulary

| Term | One-line meaning |
|---|---|
| DRM (Digital Rights Management) | Controls that raise the cost/traceability of copying digital content — deterrence, not prevention |
| Traceable deterrence | The honest goal of consumer DRM: make leaks attributable, not impossible |
| Signed URL (application-level) | A URL your own server can cryptographically verify, distinct from a storage provider's presigned URL |
| Presigned URL | A storage provider's time-limited link straight to an object — dangerous for content that must be watermarked first |
| Last-writer-wins | The newest write replaces the current value; used for the active-session pointer (`SET ... GET`) |
| First-writer-wins | The first write claims the value; later attempts are rejected; used for single-use signatures (`SET NX`) |
| Lease | "Valid until time T unless renewed" — how a server detects an abandoned client without an explicit goodbye |
| Heartbeat | A periodic "I'm still here" ping that renews a lease |
| Atomic compare-and-refresh | Checking a value and conditionally updating it in one indivisible operation (here, a Lua script) |
| Constant-time comparison | A comparison whose duration doesn't depend on where two values first differ |
| Watermark | Identifying information burned directly into an image's pixels, not removable as metadata |
| Append-only (by convention vs. enforced) | A table only ever inserted into — either because nothing in the code updates it, or because the database itself rejects UPDATE/DELETE |
| Defense in depth | Multiple independent checks stacked so defeating one alone isn't enough |
| Injectable Clock | Passing `java.time.Clock` as a dependency instead of calling `Instant.now()`, so time-based logic is testable with a fixed clock |
| Failsafe plugin | Maven's integration-test runner, bound to `*IT.java` by convention — distinct from Surefire, which runs `*Test.java` |
| Sweeper | A `@Scheduled` job that periodically cleans up state a live request path didn't get a chance to |
| `ImageBitmap` | A decoded, GPU-friendly, DOM-detached image handle from `createImageBitmap()` — must be `.close()`d manually |
| Object URL (`blob:`) | A live, page-lifetime reference to raw bytes created by `URL.createObjectURL` — a "coat-check ticket" for a `Blob` |
| `keepalive` fetch | A `fetch()` option that lets the request outlive the page unloading it, while still supporting normal headers |
| `sendBeacon` | A browser API for a guaranteed-to-send exit ping — cannot carry custom headers, so no `Authorization` |
| `pagehide` | The event that fires when a tab closes, refreshes, or navigates away — unlike `beforeunload`, reliable and not deprecated |
| DevTools-size heuristic | Inferring DevTools is open from the gap between `outerWidth`/`Height` and `innerWidth`/`Height` — a guess, not a detector |
| Custom hook (as a unit of behaviour) | A small, independently-testable `use...()` function owning one self-contained piece of lifecycle/state |
| Derived state | A value computed fresh from other state on every render, instead of stored and separately kept in sync |

---

## 10. If I had to defend this in a code review

**Strongest points**
- **D6's checklist has a test for every step**, and the two checks that collapse into one code path (signature validity + ownership) are documented as a deliberate design choice, not an oversight.
- **The right Redis primitive was chosen for each of the two opposite policies this phase needs** — last-writer-wins for sessions, first-writer-wins for signatures — and the reasoning for picking each one is written down next to the code, not just in this note.
- **Nothing in the tile-serving path can return clean bytes.** Every code path either throws before touching storage, or watermarks before returning — there's no branch that skips the watermark step.
- **The entitlement check is re-read fresh on every tile**, not cached from session start, so a revoke takes effect within one page turn, not "at the next login".
- **Fixing the project's own test-verification gap (Failsafe never bound) was in scope, not a detour** — it's the exact command this phase's definition of done runs, and it was silently not checking anything before this phase.
- **(05B) Not one line of page content ever becomes an `<img>`, an object URL, or a cached signed URL** — every one of D2/D5's constraints has a test asserting the *absence* of the thing it forbids (no `<img>` in the DOM, a fetch fired fresh per navigation), not just the presence of the thing it requires.
- **(05B) Every friction control is independently testable and independently honest about its own limit** — §3.13's table isn't just prose, it's backed by five separate hook tests, each pinned to exactly what that one control does and doesn't catch.
- **(05B) A real correctness bug (Gotcha #8, the transient unclamped-page fetch) was caught and fixed by writing the test for a boundary case (`?page=99`) that the acceptance criteria didn't explicitly ask for**, but that the D6 page-navigation spec implied ("clamped to both ends").

**Weakest points, and what I'd fix first**
1. **Redis is a single point of failure for the active-session check**, with no defined fallback if it's briefly unreachable — as written, a Redis timeout surfaces as an error on every heartbeat and tile fetch rather than a graceful degraded mode. **Fix first:** an explicit fallback that treats a Redis timeout as "assume active, but flag for reconciliation against Postgres", so a brief Redis blip doesn't interrupt every open reading session in the system.
2. **`viewer_access_logs` is append-only by convention, not enforced by a database trigger** the way `payment_events` is. Cheap to add (the exact same trigger function, reused) the moment this table needs to survive a dispute the way payment history does.
3. **No repeated/tiled watermark pattern** — a single diagonal label is easier to crop around than a tiled grid. Named as an explicit follow-up in the phase spec, and the first thing I'd build next for this feature specifically.
4. **Creators can't preview their own product in the secure viewer without buying it.** Listed as a follow-up in the spec; today a creator would need a separate entitlement (or the Phase 3 free preview) to see their own paid pages rendered.
5. **No rate limiting on the tile endpoint** — deferred to Phase 11 per the spec, same honest gap Phase 3's free-preview endpoint already has for the same reason (documented there, not re-solved here).
6. **(05B) A generic tile-fetch failure (not 403/409/410) shows a bare "Couldn't load this page / Retry" — it doesn't distinguish a dropped network connection from a genuinely broken document version.** Fine for MVP; the first improvement here would be surfacing *why* in dev tooling (a correlation ID is already returned by the backend, `X-Correlation-Id` — the frontend doesn't display it anywhere yet).
7. **(05B) The "one active session" takeover UX has no warning before it happens** — a buyer's first laptop only finds out it's been superseded on its next heartbeat (up to ~15s later) or its next page-turn attempt (409). A `viewerSessionTakenOver` subscription/push would make that instantaneous, at the cost of a new transport (WebSocket/SSE) this phase deliberately didn't introduce.

**Turning Failsafe back on, as a general lesson.** The three bugs it surfaced (§8, #4–#7) were all in test *code*, not application code, and all three were completely invisible until the harness that was supposed to run them actually ran them for the first time. That's worth remembering for any codebase, not just this one: a green CI badge is only informative about what CI is actually configured to run.

**The same lesson, once more, on the frontend (05B).** Gotcha #8 (the `?page=99` fetch) was never in the acceptance criteria as written — it surfaced because writing a *boundary-case test* (not just the happy path) is what forces you to actually trace what value flows into `useSecureTile` on the render *between* the session activating and the reclamp effect running. A test suite that only covers what the spec explicitly lists will pass while still shipping this exact bug.
