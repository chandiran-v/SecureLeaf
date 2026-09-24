# Phase 1 — Authentication & Security

> **Status:** Done (with two known bugs, fixed at the start of Phase 2 — see §8)
> **Requirement IDs covered:** AUTH-01 (register), AUTH-02 (Google OAuth2), AUTH-03 (JWT + refresh), AUTH-04 (rotation), AUTH-05 (RBAC)
> **Not yet covered:** AUTH-06 (creator profile), AUTH-07 (password reset), AUTH-08 (admin suspension)
> **Commits:** `aa02478` entities · `f151fdb` review fixes · `0272f7f` login/JWT

---

## 1. What we built, in plain English

We built the front door of the application: the ability for someone to create an account, prove who they are, and stay logged in — securely.

Three ways in: sign up with an email and password, sign in with an existing account, or sign in with Google. Once you're in, the server hands you two tokens. The first, the **access token**, is like a wristband at a festival — staff glance at it and wave you through without radioing the ticket office. It expires after 15 minutes. The second, the **refresh token**, is like the receipt you keep in your pocket: when the wristband expires, you show the receipt and get a fresh one, without re-entering your password. It lasts 7 days.

The interesting engineering is in what happens if someone *steals* your receipt. We built **rotation with reuse detection** — every time a refresh token is used, it is destroyed and replaced. If a token that was already used shows up again, that is evidence of theft, and the system kills every session for that user immediately.

**Before this phase:** no users, no login, every API call anonymous.
**After this phase:** users can register and log in (email/password or Google), the API knows who is calling on every request, and endpoints can demand specific roles.

---

## 2. Why it matters

Authentication is the foundation every other phase stands on. SecureLeaf's entire product promise is *"only the person who paid can see this document, and every page they see is watermarked with their identity."* That sentence is meaningless if the server cannot reliably answer **"who is this?"** on every single request.

It is also the gate for **authorization** — creators may only edit their own products, buyers may only view documents they bought, admins may take content down. None of that can be built until identity is solid.

---

## 3. New concepts introduced

### 3.1 Hashing vs. encryption (and why passwords are never encrypted)

**What it is:** Hashing is a one-way transformation. You can turn `hunter2` into a long string of gibberish, but you cannot turn the gibberish back into `hunter2`. Encryption is two-way — anything encrypted can be decrypted with the key.

**The analogy:** Encryption is a locked box. Hashing is a blender. You can prove a smoothie came from a specific banana by blending an identical banana and comparing — but you can never reassemble the banana.

**Why we needed it here:** If our database leaks, plaintext passwords would hand the attacker every user's account — and, because people reuse passwords, their email and bank accounts too. With hashes, the attacker gets gibberish.

**In our code:** `auth/security/SecurityConfig.java:66-68`
```java
@Bean
public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
}
```

**Why BCrypt specifically, and not SHA-256?** This is a classic interview trap. SHA-256 is *fast* — that is a virtue for checksums and a catastrophe for passwords, because fast means an attacker can try billions of guesses per second on a GPU. BCrypt is **deliberately slow** and has a tunable "work factor" you increase as hardware gets faster. It also **salts** automatically: a random value is mixed into each password, so two users with the same password get different hashes, which defeats precomputed "rainbow table" attacks.

**What breaks without it:** A database leak becomes a total account compromise, for your users and for every other site they reused that password on.

---

### 3.2 Stateless authentication and JWTs

**What it is:** A **JWT** (JSON Web Token, pronounced "jot") is a string with three dot-separated parts: a header, a payload of claims (who you are, what roles you have, when it expires), and a **signature**. It is **signed, not encrypted** — anyone can read the contents, but nobody can change them without invalidating the signature.

**The analogy:** A passport. Everyone can read your name and date of birth. Nobody can alter them without destroying the holograms that prove authenticity.

**Why we needed it here:** The traditional alternative is **server-side sessions** — the server stores "session abc123 = user 42" in memory and the browser sends a cookie. That breaks the moment you run more than one server, because server B has never heard of a session created on server A. You then need sticky sessions or a shared session store, which is extra infrastructure to run and to fail.

A JWT carries the identity *inside the token itself*. Any server holding the signing secret can verify it independently. No lookup, no shared state. That is what **stateless** means.

**In our code:** `auth/service/JwtService.java` signs with HS256; the subject is the user id, plus `email` and `roles` claims. `SecurityConfig.java:48` turns Spring's own sessions off entirely:
```java
.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
```

**The catch — and interviewers always ask this:** because verification requires no database lookup, **a JWT cannot be revoked**. If you ban a user, their access token keeps working until it expires. That is precisely why our access token lives only **15 minutes** — it bounds the damage window. The long-lived credential (7 days) is the refresh token, which *is* stored in the database and *can* be revoked instantly.

That is the central trade-off of this design: **short-lived unrevocable token + long-lived revocable token.**

---

### 3.3 Refresh token rotation and reuse detection

**What it is:** Every time a refresh token is exchanged for a new access token, the old refresh token is marked revoked and a brand-new one is issued. Each token is usable exactly once.

**Why it matters:** Without rotation, a stolen refresh token is a 7-day skeleton key and nobody ever notices. With rotation, theft becomes *detectable*.

**How the detection works:**
1. Attacker steals your refresh token `R1`.
2. Attacker uses `R1` first. Server rotates: `R1` revoked, `R2` issued to the attacker.
3. Later, your legitimate app tries `R1` — which is already revoked.
4. **A revoked token being presented means two parties hold the same token.** That is theft, by definition.
5. Server revokes *every* refresh token for that user. Both of you are logged out. You log in again with your password; the attacker cannot.

**In our code:** `auth/service/AuthService.java:112-148`
```java
if (storedToken.getRevokedAt() != null) {
    boolean isGracePeriod = storedToken.getReplacedBy() != null &&
            storedToken.getRevokedAt().plusSeconds(30).isAfter(Instant.now());

    if (!isGracePeriod) {
        log.warn("Refresh token reuse detected outside grace period for user id={}", ...);
        revokeAllUserTokens(storedToken.getUser());
        throw new BusinessException(ErrorCode.TOKEN_REUSE, ...);
    }
}
```

**Why the 30-second grace window?** Real life is messier than the theory. If a user has three browser tabs open and the token expires, all three may fire a refresh at the same instant. One wins; the other two present a token that was revoked milliseconds ago — and would be logged out as "thieves". The grace window says: *if this token was revoked very recently and we know what replaced it, it is a race, not an attack.* This is a genuinely good detail to raise in an interview, because it shows you think about real users and not just the happy path.

---

### 3.4 Why refresh tokens are hashed too — with SHA-256, not BCrypt

Our refresh token is **not** a JWT. It is an opaque random UUID, and the database stores only its **SHA-256 hash**:

`auth/service/AuthService.java:163-173` stores `jwtService.hashToken(rawRefreshToken)`; the raw value is returned to the client and never persisted.

**Two separate ideas here, and interviewers love to separate them:**

**Why hash it at all?** Same reason as passwords — a database leak would otherwise hand the attacker live, working sessions for every logged-in user.

**Why SHA-256 here, when we insisted on BCrypt for passwords?** Because the threat model is different. BCrypt is slow to defeat *brute-force guessing*, which matters for passwords because humans choose weak, guessable ones. A refresh token is a **128-bit cryptographically random UUID** — it is not guessable at any speed; brute force is off the table. What we need is a fast, deterministic fingerprint we can look up on every refresh call. Using BCrypt here would add real latency for zero security gain — and worse, BCrypt's random salt means you cannot look a value up by hash at all.

> **Rule of thumb:** hash *low-entropy human secrets* (passwords) slowly; hash *high-entropy machine secrets* (tokens, API keys) fast.

---

### 3.5 The Spring Security filter chain

**What it is:** Every HTTP request passes through an ordered pipeline of filters before it reaches your code. Each filter may inspect, modify, or reject the request.

**The analogy:** Airport security. Boarding pass check, then bag scan, then metal detector — in that order, every time, for everyone.

**Our custom filter:** `auth/security/JwtAuthenticationFilter.java` extends `OncePerRequestFilter` and, on each request, reads the `Authorization: Bearer <token>` header, validates the signature, loads the user with their roles, and places a populated `Authentication` object into the `SecurityContextHolder`. From that point on, any code in the request can ask "who is calling?".

**Placement matters:** `SecurityConfig.java:59`
```java
.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
```
It must run *before* Spring's form-login filter, so our token is considered first.

**A subtle, deliberate choice:** when the token is invalid or the account is suspended, the filter does **not** throw — it simply continues the chain *unauthenticated* (`JwtAuthenticationFilter.java:64-67`). Later authorization rules then produce a clean "access denied" rather than a confusing low-level error. This also lets genuinely public operations (the `login` mutation itself) pass through with a junk header present.

---

### 3.6 Authentication vs. Authorization vs. object-level authorization

Three different questions, constantly confused — keeping them straight is a strong signal in an interview:

| | Question | Our mechanism |
|---|---|---|
| **Authentication** | *Who are you?* | JWT validated by `JwtAuthenticationFilter` |
| **Authorization (RBAC)** | *Are you allowed to do this kind of thing?* | `@PreAuthorize("hasRole('CREATOR')")` |
| **Object-level authorization** | *Are you allowed to do it to **this specific** record?* | ownership check inside the service |

**Why the third one is not optional.** `@PreAuthorize("hasRole('CREATOR')")` on "delete product" means *any* creator can call it — including on *someone else's* product. Role checks answer "what kind of user are you", never "is this yours". The ownership check must live in the service layer, where the record is actually loaded:

```java
if (!product.getCreator().getId().equals(currentUserId)) {
    throw new AccessDeniedException(...);
}
```

This class of bug is #1 on the OWASP API Security Top 10 — **Broken Object Level Authorization (BOLA)**. It is also the single most common real-world API vulnerability. Our project plan calls it out explicitly (`docs/project_plan.md:64-66`), and Phase 2 implements it via `assertOwnership`.

**In our code today:** `SecurityConfig.java:37` enables `@EnableMethodSecurity`, which is what makes `@PreAuthorize` work at all. `SecureLeafUserDetails` converts our `Role` enum into Spring's expected `"ROLE_" + name` format — Spring's `hasRole('CREATOR')` silently looks for an authority literally named `ROLE_CREATOR`, a naming convention that trips up almost everyone the first time.

---

### 3.7 OAuth2 "Sign in with Google" — what actually happens

**The misconception:** Google does not send us your password. It never does. We never see it.

**The real flow:**
1. The browser talks to Google directly; the user authenticates there.
2. Google returns an **ID token** — a JWT signed by *Google's* private key — to our frontend.
3. Our frontend sends that ID token to our backend.
4. Our backend verifies it against **Google's public keys**, fetched from `https://www.googleapis.com/oauth2/v3/certs` (`auth/config/GoogleOAuthConfig.java`).

**The checks that make this safe** (`auth/service/GoogleOAuthService.java`) — an interviewer may well ask for exactly these:
- **Signature** — proves Google issued it and it hasn't been tampered with.
- **Issuer** — must be `https://accounts.google.com`.
- **Audience** — must equal *our* client id. **This is the one people forget.** Without it, an attacker could take a valid Google token issued for *a completely different app* and replay it against us. The audience claim is what binds the token to our application.
- **`email_verified`** must be true, so nobody claims an unverified address.

**A design decision worth defending:** we did **not** use Spring's built-in `oauth2Login()` — it is explicitly disabled at `SecurityConfig.java:61`. That feature is designed for server-rendered apps with redirect flows. We have a React single-page app talking GraphQL, so we verify the ID token ourselves in a `googleLogin` mutation. Consistent API surface, no redirect dance.

---

### 3.8 CSRF and CORS — two different things with confusingly similar names

**CSRF (Cross-Site Request Forgery):** a malicious site tricks *your browser* into making a request to our API, riding on credentials the browser sends automatically. We **disable CSRF protection** (`SecurityConfig.java:46`) and that is correct here — CSRF attacks depend on *automatic* credential sending, which means cookies. We use `Authorization` headers, which a browser never attaches on its own. An attacker's site cannot read our token to add the header.

> If we ever move tokens into cookies (a real option, see §10), **CSRF protection must come back on.** Those two decisions are linked.

**CORS (Cross-Origin Resource Sharing):** a browser rule that stops JavaScript on `evil.com` from reading responses from `our-api.com`. Our frontend runs on port 5173 and our API on 8080 — **different origins**, so we must explicitly allow it (`SecurityConfig.java:71-82`).

Note we list exact origins rather than using a wildcard. That is required: `allowCredentials(true)` combined with `*` is forbidden by the spec, and wildcarding origins is bad practice regardless.

---

### 3.9 Database migrations with Flyway

**What it is:** Schema changes as **numbered, immutable SQL files** checked into git. Flyway tracks which have run and applies only the new ones, in order.

**Why not let Hibernate generate the schema?** `spring.jpa.hibernate.ddl-auto` can auto-create tables from entities, which is seductive in development and dangerous everywhere else: you cannot review it, cannot write a rollback, cannot add a carefully chosen index, and it will happily drop a column — and its data. We set it to **`validate`** (`application.yml`), meaning: *check that the entities match the database, and refuse to start if they don't.* Best of both worlds — full control over the schema, plus an automatic tripwire when an entity drifts.

**The golden rule:** applied migrations are **immutable**. Never edit `V1` after it has run somewhere; write `V3`. Flyway checksums each file and will refuse to start if history was rewritten — that check is protecting you.

**In our code:** `db/migration/V1__init_schema.sql` (18 tables, 11 enums), `V2__fix_ip_address_type.sql`. Note that `V2` exists *because* of the golden rule — the `INET` type was changed to `VARCHAR(45)` in a new file rather than by editing `V1`.

---

## 4. Best practices applied

| Practice | What we did | Why it matters | Where |
|---|---|---|---|
| Never store plaintext credentials | BCrypt for passwords, SHA-256 for refresh tokens | A DB leak yields nothing usable | `SecurityConfig.java:66`, `AuthService.java:168` |
| Short-lived access tokens | 15 minutes | Bounds the window of an unrevocable token | `JwtProperties.java:20` |
| Refresh token rotation | One-time use, replaced on every refresh | Makes theft detectable, not just possible | `AuthService.java:136-146` |
| Fail closed | Unknown/suspended user → unauthenticated, not authenticated | Errors deny access rather than grant it | `JwtAuthenticationFilter.java:64-67` |
| Don't leak which half was wrong | "Invalid email or password" for both cases | Prevents email enumeration | `AuthService.java:78,86` |
| Normalize before comparing | `email.toLowerCase().trim()` | `Bob@x.com` and `bob@x.com` are one account | `AuthService.java:43-45` |
| Defend at the database, not just in code | `UNIQUE` on email, `CHECK` constraints | Two concurrent signups can both pass an `exists()` check; only the DB can truly enforce it | `V1__init_schema.sql:33-34` |
| Handle the race you just created | Catch `DataIntegrityViolationException` → friendly duplicate error | The constraint fires under concurrency; don't show a 500 | `AuthService.java:63-65` |
| Pessimistic locking on token rotation | `@Lock(PESSIMISTIC_WRITE)` on token lookup | Two simultaneous refreshes must not both succeed | `RefreshTokenRepository` |
| Externalize all secrets | `${JWT_SECRET}`, `${DB_PASSWORD}` env vars | No credentials in git | `application.yml` |
| Validate config at startup | `@Validated` + `@Size(min=32)` on the JWT secret | Fail loudly at boot, not silently at runtime | `JwtProperties.java` |
| Never expose entities through the API | `UserDto` + `UserMapper` | Prevents leaking `passwordHash`; decouples API from schema | `auth/dto/`, `auth/mapper/` |
| Centralized error handling | `GlobalGraphQlExceptionHandler` | Consistent shape; internal details never leak to clients | `common/exception/` |
| Typed error codes | `ErrorCode` enum in `extensions.code` | Clients branch on a stable code, not on message text | `common/exception/ErrorCode.java` |
| Correlation ids for unknown errors | Random UUID logged and returned | A user can quote an id; the log has the stack trace | `GlobalGraphQlExceptionHandler` |
| Input validation at the edge | `@Valid`, `@Email`, `@Size` on DTOs | Rejects bad input before it reaches business logic | `auth/dto/` |

---

## 5. What does what — file map

### Backend

| File | Responsibility |
|---|---|
| `auth/entity/User.java` | The user table: email, password hash, provider, status, soft-delete |
| `auth/entity/UserRole.java` + `Role.java` | Many-to-many roles (`BUYER`/`CREATOR`/`ADMIN`) via composite key |
| `auth/entity/RefreshToken.java` | Hashed token, expiry, `revokedAt`, and `replacedBy` — the rotation chain |
| `auth/repository/UserRepository.java` | Queries; `findWithRoles*` use `@EntityGraph` to avoid the N+1 problem |
| `auth/repository/RefreshTokenRepository.java` | Pessimistic-locked lookup; bulk revoke |
| `auth/service/JwtService.java` | Signs and validates JWTs; SHA-256 token hashing |
| `auth/service/AuthService.java` | **All business logic**: register, login, rotate, revoke, Google login |
| `auth/service/GoogleOAuthService.java` | Verifies Google ID tokens (signature, issuer, audience, email_verified) |
| `auth/service/SecureLeafUserDetails.java` | Adapts our `User` to Spring's `UserDetails`; maps roles to `ROLE_*` |
| `auth/security/JwtAuthenticationFilter.java` | Per-request: read header → validate → populate `SecurityContext` |
| `auth/security/SecurityConfig.java` | The filter chain, public routes, CORS, password encoder |
| `auth/resolver/AuthResolver.java` | GraphQL entry points — **thin**, delegates immediately to the service |
| `auth/mapper/UserMapper.java` | Entity → DTO |
| `common/exception/*` | Business exception types + the global GraphQL error handler |

**Layering, and why it is strict:** `Resolver → Service → Repository → Database`. The resolver has no business logic; the service has no HTTP/GraphQL awareness; the repository has no business rules. Keeping those boundaries clean means the service can be tested without a web layer, and the API can change shape without touching business logic.

### Request trace — logging in

1. **`LoginPage.tsx`** submits → `useAuth().login()`
2. **Apollo Client** sends the `login` mutation to `/graphql`
3. **`SecurityConfig`** — `/graphql` is permitAll, so the request passes
4. **`JwtAuthenticationFilter`** — no token present; continues unauthenticated (fine, login is public)
5. **`AuthResolver.login()`** — `@Valid` runs on `LoginInput`
6. **`AuthService.login()`** — normalize email → load user *with roles* → reject if this is a Google account → `passwordEncoder.matches()` → check account status
7. **`generateTokens()`** — sign a 15-min JWT; create a UUID refresh token; persist **only its SHA-256 hash**
8. Response: `{ accessToken, refreshToken, user }`
9. **`authStore`** (Zustand + persist) saves it; `apolloClient`'s `authLink` attaches `Bearer` on every later request

### Frontend

| File | Responsibility |
|---|---|
| `store/authStore.ts` | Zustand store, persisted to localStorage |
| `graphql/apolloClient.ts` | `authLink` injects the Bearer header; `errorLink` auto-refreshes on `UNAUTHORIZED` and replays the failed request |
| `hooks/useAuth.ts` | All auth operations in one hook |
| `components/layout/ProtectedRoute.tsx` | Redirects anonymous users to `/login`, remembering where they came from |

**The transparent-refresh pattern** in `apolloClient.ts` is worth understanding: when any request fails as unauthorized, the error link refreshes the token and **replays the original operation**, so the user never sees a flicker or a logout. This is the standard production pattern for SPA token handling.

---

## 6. Design decisions and trade-offs

### Decision: JWT access token + database-backed refresh token
- **Alternatives:** pure server-side sessions; long-lived JWT with no refresh; JWT with a Redis denylist.
- **Why this:** stateless verification on the hot path (no DB hit per request) *plus* a real revocation mechanism for the long-lived credential.
- **Gave up:** instant revocation of access tokens — a banned user keeps access for up to 15 minutes.
- **Revisit when:** we need immediate ban enforcement; then add a Redis denylist checked per request, trading some statelessness back.

### Decision: refresh token is an opaque UUID, not a JWT
- **Alternatives:** make it a JWT too.
- **Why this:** its only job is to be looked up in a table, and it is already stored server-side, so JWT's self-describing property buys nothing — while adding size and a second thing to parse. Opaque tokens are also trivially revocable.
- **Gave up:** nothing meaningful.

### Decision: tokens in localStorage rather than httpOnly cookies
- **Alternatives:** httpOnly, Secure, SameSite cookies.
- **Why this:** simple for an SPA; immune to CSRF; no cookie/CORS complications.
- **Gave up:** **XSS resistance.** Any injected script can read localStorage and steal the tokens. httpOnly cookies cannot be read by JavaScript at all.
- **Honest assessment:** *this is the weakest security decision in the phase.* The rigorous answer is httpOnly cookies plus CSRF tokens. Saying this out loud in an interview — naming your own design's weak point and the correct fix — is far stronger than defending it.

### Decision: one account can be both buyer and creator
- **Alternatives:** separate account types.
- **Why this:** matches how Gumroad works and how people behave — the same person sells notes and buys others'. A `user_roles` join table makes multi-role natural.
- **Gave up:** simpler permission logic that a single `role` column would give.

---

## 7. Interview questions

### Beginner

**Q: What is a JWT, and what are its three parts?**
A: A signed token carrying identity claims. Header (algorithm), payload (claims — user id, roles, expiry), and signature. It's Base64-encoded, so it's readable by anyone — signed, not encrypted. I'd never put secrets in the payload.

**Q: Why store a password hash instead of the password?**
A: So a database breach doesn't expose credentials. Hashing is one-way — we can verify a password by hashing the attempt and comparing, but never recover the original.

**Q: What's the difference between authentication and authorization?**
A: Authentication is "who are you" — our JWT filter. Authorization is "what are you allowed to do" — our `@PreAuthorize` role checks.

**Q: Why have two tokens instead of one?**
A: To resolve a conflict. Short expiry is secure but would force a login every 15 minutes. Long expiry is convenient but dangerous. Two tokens give both: a short-lived access token limits damage, and a revocable refresh token preserves the user experience.

### Intermediate

**Q: Why BCrypt for passwords but SHA-256 for refresh tokens?**
A: Different threat models. Passwords are low-entropy and human-chosen, so the threat is offline brute force — BCrypt is deliberately slow and salted to make that expensive. A refresh token is a 128-bit random UUID; guessing it is infeasible regardless of hash speed. There I want a fast deterministic hash I can index and look up. BCrypt would add latency for no gain, and its random salt would make lookup-by-hash impossible.

**Q: Explain refresh token rotation and reuse detection.**
A: Each refresh token is single-use — using it revokes it and issues a replacement. If a revoked token is presented again, two parties hold the same token, which means it was stolen. We respond by revoking every session for that user, forcing a password login the attacker can't complete. We allow a 30-second grace window when the token has a known replacement, so multiple browser tabs racing on refresh aren't misread as theft.

**Q: You disabled CSRF. Isn't that a vulnerability?**
A: Not in this design. CSRF exploits credentials the browser attaches *automatically*, which means cookies. We send tokens in an `Authorization` header that our JavaScript sets explicitly, and an attacker's page can't read our token to construct that header. If we moved to cookie-based tokens, CSRF protection would have to come back on.

**Q: How do you verify a Google sign-in?**
A: The frontend gets an ID token from Google and sends it to us. We verify its signature against Google's published public keys, check the issuer is `accounts.google.com`, and — critically — check the **audience** equals our client id, so a token issued for another app can't be replayed against us. We also require `email_verified`.

**Q: What is `ddl-auto: validate` and why not `update`?**
A: `validate` checks entities against the existing schema and refuses to start on mismatch; `update` lets Hibernate alter the schema itself. We use Flyway migrations for all DDL because they're reviewable, versioned, and rollback-planned. `update` is unpredictable, can't express indexes or constraints properly, and can destroy data. `validate` gives us an automatic tripwire for entity drift.

### Advanced

**Q: A JWT can't be revoked. How do you handle banning a user?**
A: Three layers. Access tokens live 15 minutes, so the exposure is bounded. Refresh tokens are in the database, so revoking them stops renewal immediately. And our JWT filter re-loads the user on every request and refuses to authenticate a suspended or soft-deleted account — so in practice a ban takes effect on the next request. That last check does cost a DB lookup, trading some statelessness for immediate enforcement. If I wanted zero DB hits, I'd use a Redis denylist keyed by token id instead.

**Q: Two requests refresh the same token simultaneously. What happens?**
A: The repository lookup uses `@Lock(PESSIMISTIC_WRITE)`, so the database serializes them — `SELECT ... FOR UPDATE`. The first rotates the token and commits. The second then sees it already revoked, but finds `replacedBy` set and the revocation under 30 seconds old, so the grace window treats it as a race rather than theft. Without both the lock and the grace window, concurrent tabs would log users out constantly.

**Q: Where would you place a rate limiter, and why?**
A: On login and refresh, keyed by IP and by email. Login is the brute-force target, and without a limit the BCrypt work factor only slows an attacker, it doesn't stop them. I'd use a filter ahead of the resolvers with Redis-backed token buckets — Bucket4j. We already have an unused `RATE_LIMITED` code in our `ErrorCode` enum, so the intent was there; it just isn't implemented yet.

**Q: Why return the same error for a wrong password and a nonexistent email?**
A: To prevent user enumeration. If "no such user" and "wrong password" differed, an attacker could harvest valid email addresses and then target them for credential stuffing or phishing. Worth noting the timing side channel too — an early return on unknown email is measurably faster than one that runs BCrypt, so a rigorous implementation hashes a dummy value to equalize timing.

### "Tell me about a bug you fixed"

**Q: Describe a subtle bug you found in your own code.**
A: Our `me` query was silently broken. `getUserById` used `findById` instead of the roles-fetching variant and wasn't `@Transactional`, so mapping to a DTO touched the lazily-loaded roles collection after the Hibernate session had closed — with `open-in-view: false`, that's a `LazyInitializationException`.

What made it interesting is that it was *invisible*. The frontend ran that query with Apollo's `errorPolicy: 'ignore'`, so the failure was swallowed and the app silently fell back to the stale user in localStorage. It looked like it worked.

I found it reading the code rather than from a bug report. The fix was small — use the existing `@EntityGraph` query and add `@Transactional(readOnly = true)`. But it taught me two things: `open-in-view: false` is the right setting *and* it converts hidden lazy-loading into real failures you must handle explicitly; and blanket error suppression on the client turns backend bugs into ghosts. I added a regression test so it can't come back silently.

---

## 8. Gotchas and bugs we hit

| Symptom | Root cause | Fix | Lesson |
|---|---|---|---|
| `me` query fails silently; stale user shown | `getUserById` used `findById`, not `findWithRolesById`, and wasn't transactional → `LazyInitializationException` with `open-in-view: false` | Use the `@EntityGraph` query + `@Transactional(readOnly = true)` | Lazy loading fails *outside* the transaction; the DTO mapping must happen inside it |
| Logout appears to work but the token stays valid 7 days | Frontend sent `mutation Logout { logout }` with no argument, but the schema requires `refreshToken: String!`; the validation error was swallowed by a `try/catch` | Pass the variable; route the button through `useAuth().logout` | A `catch` that ignores the error hides a broken contract. Keep client and schema in sync — ideally with codegen |
| `hasRole('CREATOR')` never matches | Spring expects the authority string `ROLE_CREATOR` | `SecureLeafUserDetails` prefixes `"ROLE_"` | `hasRole(X)` checks `ROLE_X`; `hasAuthority(X)` checks `X` exactly |
| Duplicate users possible under load | `existsByEmail` then `save` is check-then-act — two requests can both pass the check | `UNIQUE` constraint + catch `DataIntegrityViolationException` | Only the database can enforce uniqueness under concurrency |
| Concurrent tabs logging the user out | Parallel refreshes look like token reuse | Pessimistic lock + 30-second grace window | Design for concurrent clients, not a single tab |

---

## 9. New vocabulary

| Term | One-line meaning |
|---|---|
| **JWT** | Signed token carrying identity claims; readable by anyone, forgeable by no one |
| **Claim** | A key/value fact inside a JWT (`sub`, `roles`, `exp`) |
| **Bearer token** | "Whoever holds this is authorized" — sent as `Authorization: Bearer <token>` |
| **Stateless auth** | Server keeps no session; the token carries the identity |
| **Salt** | Random value mixed into a hash so identical inputs produce different outputs |
| **Work factor** | BCrypt's tunable slowness dial |
| **Token rotation** | Replacing a refresh token on every use |
| **Reuse detection** | Treating a second use of a one-time token as evidence of theft |
| **RBAC** | Role-Based Access Control |
| **BOLA** | Broken Object Level Authorization — acting on records that aren't yours; OWASP API #1 |
| **CSRF** | Tricking a browser into sending an authenticated request you didn't intend |
| **CORS** | Browser rules governing cross-origin requests |
| **Filter chain** | Ordered pipeline every HTTP request passes through |
| **Lazy loading** | Hibernate fetching a relation only when touched — fails outside the session |
| **N+1 query** | 1 query for a list + N more for each item's relation; solved with `@EntityGraph`/`JOIN FETCH` |
| **Idempotent** | Doing it twice has the same effect as doing it once |
| **Pessimistic lock** | `SELECT ... FOR UPDATE` — block others until this transaction commits |
| **Fail closed** | On error, deny access (vs. fail open, which grants it) |
| **User enumeration** | Learning which accounts exist from differing responses |

---

## 10. If I had to defend this in a code review

**Strongest points:**
- Refresh token rotation *with* reuse detection and a grace window — most tutorial implementations skip detection entirely, and nearly all skip the grace window that makes it usable with real multi-tab clients.
- Layering is clean: resolvers are thin, all logic sits in the service, and the repository stays dumb. It is testable without a web layer.
- Defense in depth on identity: unique constraints *and* application checks, status checks in the filter *and* in the service.
- Config is externalized and validated at startup, so a missing or weak `JWT_SECRET` fails at boot rather than in production.

**Weakest point, and what I'd fix first:**
Tokens live in `localStorage`, which is readable by any injected script — a single XSS becomes full account takeover. The correct design is httpOnly + Secure + SameSite cookies with CSRF protection re-enabled. I'd fix that before any real launch.

**Second:** there is no rate limiting on login. BCrypt slows an attacker but doesn't stop them. `ErrorCode.RATE_LIMITED` already exists but nothing throws it.

**Third:** zero automated tests at the end of this phase — genuinely a gap, and the reason Phase 2 is written test-first.
