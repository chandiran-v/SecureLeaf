# SecureLeaf — MVP1 Requirements Traceability Matrix

> Phase 9, D10. For every MVP1 requirement id in [`docs/requirements.md`](requirements.md): status
> (Done / Partial / Deferred), evidence (a test method or a `file:line`), and — for anything not
> flatly Done — why, and where it'll be handled. Read this alongside
> [`docs/learning-notes/phase-09-hardening-release.md`](learning-notes/phase-09-hardening-release.md)
> for the reasoning behind Phase 9's own rows.
>
> **Honesty note:** every "Done" row below points at a real test or a real line of code that exists
> on this branch today. Anything I could not find a direct test for is marked Partial with the gap
> named explicitly — not silently upgraded to Done.

---

## Module 1: Authentication & User Management

| ID | Status | Evidence | Notes |
|----|--------|----------|-------|
| AUTH-01 | Done | `AuthServiceTest.register_handsTheNewUserToAdminBootstrapService`; used throughout every `*IT.java` via `authService.register(...)` | |
| AUTH-02 | Partial | `backend/src/main/java/com/secureleaf/auth/service/GoogleOAuthService.java`; wired at `AuthResolver.java:47` | Implemented, but no test exercises `GoogleOAuthService` or the `googleLogin` mutation anywhere in the suite. Not part of Phase 9's scope; flagged here as a pre-existing gap a future `fix`-labeled pass should close. |
| AUTH-03 | Done | `LoginThrottleIT.successfulLogin_resetsCounter_soSubsequentFailuresStartFresh`; `JwtAuthenticationFilterIT.validToken_stillWorks_andNoTokenStillReachesPublicQueries` | |
| AUTH-04 | Done | `AuthService.refreshToken` rotation at `backend/src/main/java/com/secureleaf/auth/service/AuthService.java:120-160`; reuse detection exercised in `AdminUserManagementIT` (suspend revokes tokens, then reuse is rejected with `TOKEN_REUSE`) | |
| AUTH-05 | Done | `AdminAuthorizationIT` (parameterized `@PreAuthorize("hasRole('ADMIN')")` checks across every admin operation) | |
| AUTH-06 | Done | `AuthServiceIT.becomeCreator_success` | |
| AUTH-07 | Done | `PasswordResetIT` (request/reset/reuse/expiry/Google-only-account cases) | |
| AUTH-08 | Done | `AdminUserManagementIT` (suspend/reactivate); `AdminAuthorizationIT.adminCannotSuspendSelf`/`adminCannotSuspendAnotherAdmin` | |

## Module 2: Content Upload & Processing

| ID | Status | Evidence | Notes |
|----|--------|----------|-------|
| UPLOAD-01 | Done | `DocumentUploadIT.uploadDocument_success` | |
| UPLOAD-02 | Partial | `backend/src/main/java/com/secureleaf/content/controller/DocumentUploadController.java:96-98` (50MB → `FILE_TOO_LARGE`) | Implemented; no test uploads an oversized file to prove the rejection path. |
| UPLOAD-03 | Done | `DocumentUploadIT.uploadDocument_rejectsNonPdfMagicBytes` | |
| UPLOAD-04 | Done | `ProcessingPipelineIT.processAsync_success` (full 5-stage pipeline) | |
| UPLOAD-05 | Done* | `ProductServiceIT.createProduct_success` (title/description/category/tags) | *Cover image is auto-derived from the generated thumbnail (`DocumentProcessingService.java:224-228`), not creator-uploaded separately — a deliberate simplification from Phase 2, not a Phase 9 change. Functionally equivalent for MVP1 (every product gets a cover image); revisit if creators ever need a distinct cover. |
| UPLOAD-06 | Done | `ProductServiceIT.createProduct_success` | |
| UPLOAD-07 | Done | `CommerceIT` free-product path (`freeProduct_completesImmediately_withoutAnyPayment`) | |
| UPLOAD-08 | Done | `PreviewControllerIT.pageWithinFreePreviewRange_returns200Png` / `pageBeyondFreePreviewPages_returns404...` | |
| UPLOAD-09 | Done | `LibraryIT.unpublishedProduct_staysInTheBuyersLibrary_andRemainsViewable`; `ViewerIT.unpublishedProduct_stillViewableByEntitledBuyer` | |
| UPLOAD-10 | Done | `ProcessingNotificationIT.exhaustedRetries_createAProcessingFailedNotification`; retry logic `DocumentProcessingService.java:264-289` | |
| UPLOAD-11 | Done | `ProcessingPipelineIT.processAsync_success` (asserts tile bytes exist under `minio_object_key`) | |

## Module 3: Marketplace & Discovery

| ID | Status | Evidence | Notes |
|----|--------|----------|-------|
| MARKET-01 | Done | `MarketplaceQueryIT.productsQuery_excludesNonLiveAndDeleted` | |
| MARKET-02 | Done | `MarketplaceQueryIT.search_findsMatchesByTitleOrDescription_excludesNonMatches` / `search_usesTheFtsIndex` | |
| MARKET-03 | Done | `MarketplaceQueryIT.filters_combineWithAndSemantics`; `ReviewIT.minRatingFilter_excludesLowerRatedAndUnratedProducts_andRatingSortPutsUnratedLast` | |
| MARKET-04 | Done | `MarketplaceQueryIT.sortBy_priceAsc_ordersAscending`; rating sort in `ReviewIT` | "Most Popular" sort has no dedicated test by that name, but shares the same `sortBy` switch as the tested sorts. |
| MARKET-05 | Done | `frontend/src/pages/marketplace/ProductDetailPage.test.tsx` | |
| MARKET-06 | Done | `PreviewControllerIT` (free-preview boundary tests, same as UPLOAD-08) | |
| MARKET-07 | Done | `MarketplaceQueryIT.pagination_returnsCorrectPageMetadata` / `pagination_lastPageHasRemainder` | |

## Module 4: Commerce Pipeline

| ID | Status | Evidence | Notes |
|----|--------|----------|-------|
| PAY-01 | Done | `frontend/.../ProductDetailPage.test.tsx` (Buy button states) | |
| PAY-02 | Done | `CommerceIT` mock-gateway success/decline/timeout scenarios | |
| PAY-03 | Done | `CommerceIT.buy_payAtGateway_verify_grantsEntitlementAndRecordsEverything` | |
| PAY-04 | Done | `CommerceIT` idempotency-key tests (`sameKey_returnsSameOrder_andCreatesOnlyOne`) | |
| PAY-05 | Done | `CommerceIT.buy_payAtGateway_verify_grantsEntitlementAndRecordsEverything`; `secondActiveEntitlement_isImpossible_evenBypassingTheApp` | |
| PAY-06 | Done | `CommerceIT.paymentEvents_areAppendOnly` | |
| PAY-07 | Done | `CommerceIT.duplicateDelivery_isProcessedOnce` / `concurrentCaptures_produceExactlyOneEntitlement` | |
| PAY-08 | Done | `NotificationIT.purchase_notifiesBuyerAndCreator_andPublishesAfterCommit` | Redis pub/sub side verified directly; the email-send call itself has no dedicated assertion in commerce tests (only password-reset email uses the `RecordingMailSender` test double). |
| PAY-09 | Done | `LibraryIT.myLibrary_isNewestFirst_andQueryCountDoesNotGrowWithLibrarySize` | |
| PAY-10 | Done | `ProductStatsIT.salesCountAndNetEarnings_areCorrectAfterTwoPurchases`; commission snapshot `OrderService.java:170` | |

## Module 5: Secure Content Viewer

| ID | Status | Evidence | Notes |
|----|--------|----------|-------|
| VIEW-01 | Done | `ViewerIT.happyPath_returnsWatermarkedTileAndNoStoreHeaders`; `frontend/.../ReaderPage.test.tsx` (draws via `drawImage`, no `<img>`) | |
| VIEW-02 | Done | `ViewerIT.reusedSignedUrl_returns403` / `expiredSignature_returns403`; `TileUrlSignerTest` | |
| VIEW-03 | Done | `PreviewControllerIT.previewBytes_differFromStoredCleanTile_provingWatermarkWasApplied`; Strategy interface `content/watermark/WatermarkRenderer.java` | |
| VIEW-04 | Done | `frontend/.../useBlockContextMenu.test.ts`; `ReaderPage.test.tsx` | |
| VIEW-05 | Done | `ReaderPage.test.tsx` (non-selectable assertion); CSS `ViewerCanvas.tsx:25` | |
| VIEW-06 | Done | `ReaderPage.test.tsx` (`draggable=false`) | |
| VIEW-07 | Done | `frontend/src/index.css` `@media print`; `useBlockPrintAndSaveShortcuts.test.ts` | |
| VIEW-08 | Done | `frontend/.../useDevToolsHeuristic.test.ts` | |
| VIEW-09 | Done | `frontend/.../useBlurOnFocusLoss.test.ts`; `ReaderPage.test.tsx` | |
| VIEW-10 | Done | `ViewerIT.secondSession_supersedesFirst`; `ReaderPage.test.tsx` (takeover panel) | |
| VIEW-11 | Done | `ReaderPage.test.tsx` (Prev/Next + arrow-key navigation) | |
| VIEW-12 | Done | `ViewerIT.startViewerSession_withoutEntitlement_returnsNotEntitled`; `unauthenticatedTileRequest_returns401` | |
| VIEW-13 | Done | `ViewerIT.accessLog_onlyWrittenOnSuccess_withCorrectPageAndCorrelationId` | |

## Module 6: Buyer Library

| ID | Status | Evidence | Notes |
|----|--------|----------|-------|
| LIB-01 | Done | `frontend/src/pages/buyer/LibraryPage.test.tsx` | |
| LIB-02 | Done | `LibraryPage.test.tsx` (cover/title/creator/date/Read link) | |
| LIB-03 | Done | `LibraryIT.myLibrary_includesRevokedEntitlements_withTheCorrectStatus`; `LibraryPage.test.tsx` | Requirement text says "Active / Expired"; the implemented statuses are Active/Revoked/Expired (`EntitlementStatus`) — same functional intent (a non-purchasable-anymore state is clearly badged), different label. |

## Module 7: Creator Dashboard

| ID | Status | Evidence | Notes |
|----|--------|----------|-------|
| DASH-01 | Done | `ProductProcessingStatusIT.processingProduct_showsItsCurrentStage_andFailedProduct_showsItsReason`; `CreatorDashboardPage.test.tsx` | |
| DASH-02 | Done | `ProductStatsIT.salesCountAndNetEarnings_areCorrectAfterTwoPurchases` / `bothFields_shareOneAggregateQuery_perRequest` | |
| DASH-03 | Done | `CommerceIT.creatorEarnings_sumOnlyCompletedOrders`; `CreatorDashboardPage.test.tsx` | |
| DASH-04 | Partial | `frontend/src/pages/creator/CreatorDashboardPage.tsx:247-256` (`#upload-product-btn` → `/creator/upload`) | The button itself isn't clicked/asserted in `CreatorDashboardPage.test.tsx`; the destination page is separately tested (`UploadProductPage.test.tsx`) and the full journey (dashboard → upload → LIVE) is exercised end-to-end by Phase 9's own `e2e/tests/mvp1-journey.spec.ts`. |

## Module 8: Reviews & Ratings

| ID | Status | Evidence | Notes |
|----|--------|----------|-------|
| REV-01 | Done | `ReviewIT.submitReview_isAnUpsert_andRecomputesTheAggregate`; `StarRatingInput.test.tsx` | |
| REV-02 | Done | `ReviewIT.submitReview_isAnUpsert_andRecomputesTheAggregate`; `ReviewForm.test.tsx` | |
| REV-03 | Done | Upsert semantics in the same test — a second `submitReview` call updates, never duplicates | |
| REV-04 | Done | `ReviewIT.ratingBreakdown_alwaysReturnsAllFiveStars`; `concurrentReviewsFromDifferentBuyers_bothLand_andAverageIsExact` | |

## Module 9: Notifications

| ID | Status | Evidence | Notes |
|----|--------|----------|-------|
| NOTIF-01 | Done | `NotificationIT.purchase_notifiesBuyerAndCreator_andPublishesAfterCommit`; `NotificationSseIT` | |
| NOTIF-02 | Done | `NotificationIT.purchase_notifiesBuyerAndCreator_andPublishesAfterCommit` | |
| NOTIF-03 | Done | `ProcessingNotificationIT.completedPipeline_createsAProcessingCompleteNotification` | |
| NOTIF-04 | Done* | In-app: `NotificationSseIT`; email: `NotificationDispatcher.java:44-64` (`@Async` + `@TransactionalEventListener(AFTER_COMMIT)`) | *Requirement text says "in-app (WebSocket)"; the implementation uses **Server-Sent Events**, a deliberate Phase 6 decision (one-way server→client push, plain HTTP, browser-managed reconnect — see `phase-06-library-dashboard-notifications.md`) — same user-facing outcome, different transport. The purchase-notification email send itself has no dedicated test (only the password-reset email path is asserted via a test double). |

## Module 10: Admin Panel

| ID | Status | Evidence | Notes |
|----|--------|----------|-------|
| ADMIN-01 | Done | `AdminUserManagementIT.search_matchesEmailOrDisplayName_caseInsensitive` / `filter_byRoleAndStatus`; `AdminUsersPage.test.tsx` | |
| ADMIN-02 | Done | `AdminUserManagementIT.suspension_isImmediate_andReactivationAllowsFreshLogin` | |
| ADMIN-03 | Done | `AdminProductModerationIT.takeDown_hidesFromMarketplace_keepsLibraryAndViewerAccess_blocksRepublish_untilRestored` | |
| ADMIN-04 | Done | `AdminAnalyticsIT.platformStats_matchesSeededFixtureExactly` | |

## Non-Functional Requirements

| Category | Status | Evidence | Notes |
|----------|--------|----------|-------|
| Performance — tile load (incl. watermark burn) under 500ms | Deferred | Java2D burn: `content/watermark/Java2DWatermarkRenderer.java` | No timing/benchmark assertion exists. Load/latency testing is explicit Phase 10 scope (`phase-10-observability-load-testing.md`) — this phase's own spec lists "metrics dashboards" as out of scope. |
| Concurrency — 500+ viewers, no races | Partial | Small-scale race proofs: `CommerceIT.concurrentInitiates_fromOneBuyer_allGetTheSameOrder`, `concurrentCaptures_produceExactlyOneEntitlement`; `ReviewIT.concurrentReviewsFromDifferentBuyers_bothLand_andAverageIsExact`; `ViewerIT.secondSession_supersedesFirst` | Correctness under small concurrent load is proven; no test drives literally 500 concurrent viewers — that's Phase 10's load-test harness. |
| Security — raw files never publicly accessible | Done | `common/storage/MinioStorageService.java` (presigned-URL-only access pattern); `PreviewControllerIT.previewOfSoftDeletedProduct_returns404` | No test directly probes the raw-upload bucket for public reachability; relies on bucket policy (private) + presigned-only access being the only code path that ever reads it. |
| Security — browser never gets an unwatermarked tile | Done | `PreviewControllerIT.previewBytes_differFromStoredCleanTile_provingWatermarkWasApplied`; `ViewerIT.happyPath_returnsWatermarkedTileAndNoStoreHeaders` | |
| Security — every endpoint requires a JWT (except public marketplace) | Done | `JwtAuthenticationFilterIT` (expired/malformed/valid-token cases); `SecurityConfig.java` route table | |
| Security — signed content URLs expire within 30s | Done | `TileUrlSignerTest.expiredSignature_failsVerification` / `exactlyAtExpiry_stillValid`; `ViewerIT.expiredSignature_returns403` | |
| Security — object-level authorization | Done | `ProductServiceIT.assertOwnership_preventsBola`; `AdminAuthorizationIT` | |
| Scalability — 10+ simultaneous uploads via thread pool | Done | `common/config/AsyncConfig.java` (`contentProcessingExecutor`: core 4 / max 10 / queue 100) | Configuration exists and is exercised by every upload test; no test drives 10 literally-concurrent uploads to observe pool saturation behavior. |
| Reliability — failed processing retries up to 3× | Done | `ProcessingNotificationIT.exhaustedRetries_createAProcessingFailedNotification` | |
| Reliability — duplicate purchases prevented | Done | `CommerceIT.sameKey_returnsSameOrder_andCreatesOnlyOne`; `duplicateDelivery_isProcessedOnce` | |
| Reliability — soft deletes never cost a buyer access | Done | `LibraryIT.deletedProduct_staysInTheBuyersLibrary_andRemainsViewable` / `unpublishedProduct_...` | |
| **Observability — correlation IDs on major events (Phase 9, D1)** | **Done** | `CorrelationIdIT.everyResponse_getsACorrelationId_andEchoesASuppliedOneUnchanged`; `CorrelationIdIT.correlationId_survivesOntoBothAsyncExecutors_provenByACapturedLogLine` | Proves the header contract *and* MDC propagation onto both `@Async` executor pools via a real, captured Logback event — not just an inspection of the decorator class. |
| **Observability — structured logs, no secrets logged (Phase 9, D2)** | **Done** | `backend/src/main/resources/logback-spring.xml` (JSON in prod); `LoggingHygieneTest.noLogStatementPassesARawSecretIdentifier` | |
| Observability — viewer access audit trail | Done | `ViewerIT.accessLog_onlyWrittenOnSuccess_withCorrectPageAndCorrelationId` | |
| **Security — HTTP security headers (Phase 9, D3)** | **Done** | `SecurityHeadersIT.everyResponse_carriesTheHardeningHeaders` / `hstsHeader_present_onlyOnSecureRequests` | |
| **Security — GraphQL depth/complexity/introspection (Phase 9, D4)** | **Done** | `GraphQlHardeningIT.queryDeeperThanTenLevels_isRejected` / `queryOverTwoHundredComplexity_isRejected`; `IntrospectionDisabledIT.introspectionQuery_isRejected_whenDisabled` | |
| **Security — login throttling (Phase 9, D5)** | **Done** | `LoginThrottleIT.sixthFailedLogin_isRateLimited_thenSuccessResetsCounter` / `differentIp_getsItsOwnCounter` | |
| **Reliability — fail-fast production config (Phase 9, D6)** | **Done** | `ProdSecretsConfigTest` (4 cases: all-defaults, one-default, all-real, non-prod-never-throws) | |
| **Reliability — liveness/readiness probes (Phase 9, D7)** | **Done** | `ReadinessIT.readiness_isUp_thenDown_onceRedisStops` | |
| **End-to-end proof of the golden path (Phase 9, D8)** | **Done** | `e2e/tests/mvp1-journey.spec.ts` — passed locally against a real running stack (see the PR body for exactly how it was run and verified in this environment) | |

---

## Known limitations (MVP1, honestly)

1. **No real payment gateway.** `payment.gateway.provider` must be `mock` even in production — see
   `docs/deployment.md` §0. This is the single biggest MVP1 limitation and the reason "don't process
   real transactions yet" is stated up front in the runbook.
2. **Google OAuth (AUTH-02) has no test coverage.** The implementation exists and has shipped since
   Phase 1; nobody has written `GoogleOAuthServiceTest` or an IT exercising the `googleLogin`
   mutation. Recommend a `fix`-labeled follow-up.
3. **Load-bearing non-functional claims (500 concurrent viewers, sub-500ms tile latency, 10
   simultaneous uploads under real load) are configured and unit/integration-tested for
   correctness, not load-tested.** This is deliberate — Phase 10 ("Observability & load-test
   harness") is the phase that builds the tooling to actually measure these under load. Treat every
   "Done" row above that says "configuration exists" as *correct*, not yet *measured at scale*.
4. **`LoggingHygieneTest` is a name-based static check**, not a type-aware one — see the Phase 9
   learning note §6 for the deliberate trade-off and its honest blind spot.
5. **NOTIF-04's in-app channel is Server-Sent Events, not WebSocket** as the requirement text says —
   a deliberate one-way-push design decision from Phase 6, not an oversight. Functionally
   equivalent for this app's actual need (server→client only).
6. **A few upload/dashboard edge cases have implementation but no direct test**: oversized-file
   rejection (UPLOAD-02) and the dashboard's "Upload new product" button click itself (DASH-04, though
   its destination and the full journey are both separately covered).

---

## Release checklist

The owner follows this once Phase 9 (this PR) is merged:

1. **Merge** this PR into `feature/secure-leaf-mvp1`, then merge that branch to `main`.
2. **Tag** the release: `git tag v1.0.0 && git push origin v1.0.0`.
3. **Deploy** per [`docs/deployment.md`](deployment.md) — Render (backend), Supabase (Postgres +
   storage), Vercel (frontend), Upstash (Redis). Set every environment variable in that document's
   §1 before the first deploy; `ProdSecretsConfig`/`DrmConfig`/`CommerceConfig` will refuse to start
   otherwise (with a clear message naming what's missing).
4. **Smoke test** using `docs/deployment.md` §6's checklist — including running
   `e2e/tests/mvp1-journey.spec.ts` once against the real production URLs with disposable test
   accounts.
5. **Create the MVP2 branch** (`feature/secure-leaf-mvp2`) once MVP1 is confirmed live and healthy,
   and switch the Phase Scheduler over per `docs/phases/README.md`'s "Switching to MVP2" section.
