package com.secureleaf.common.exception;

import org.springframework.graphql.execution.ErrorType;

public enum ErrorCode {
    INVALID_CREDENTIALS(ErrorType.UNAUTHORIZED),
    INVALID_TOKEN(ErrorType.UNAUTHORIZED),
    TOKEN_EXPIRED(ErrorType.UNAUTHORIZED),
    TOKEN_REUSE(ErrorType.UNAUTHORIZED),
    ACCOUNT_SUSPENDED(ErrorType.FORBIDDEN),
    OAUTH_NOT_CONFIGURED(ErrorType.FORBIDDEN),
    ACCOUNT_DEACTIVATED(ErrorType.FORBIDDEN),
    ACCESS_DENIED(ErrorType.FORBIDDEN),
    DUPLICATE_EMAIL(ErrorType.BAD_REQUEST),
    RESOURCE_ALREADY_EXISTS(ErrorType.BAD_REQUEST),
    RESOURCE_NOT_FOUND(ErrorType.NOT_FOUND),
    RATE_LIMITED(ErrorType.BAD_REQUEST),
    INVALID_FILE(ErrorType.BAD_REQUEST),
    FILE_TOO_LARGE(ErrorType.BAD_REQUEST),

    // ── Commerce (Phase 4) ──────────────────────────────────────────────────
    INVALID_INPUT(ErrorType.BAD_REQUEST),
    ALREADY_OWNED(ErrorType.BAD_REQUEST),
    CANNOT_BUY_OWN_PRODUCT(ErrorType.BAD_REQUEST),
    IDEMPOTENCY_KEY_REUSED(ErrorType.BAD_REQUEST),
    INVALID_STATE_TRANSITION(ErrorType.BAD_REQUEST),
    // BAD_REQUEST, not UNAUTHORIZED: the frontend's apolloClient errorLink treats UNAUTHORIZED
    // as "JWT expired" and silently refreshes the token and retries the mutation.
    INVALID_PAYMENT_SIGNATURE(ErrorType.BAD_REQUEST),
    PAYMENT_AMOUNT_MISMATCH(ErrorType.BAD_REQUEST),
    ORDER_NOT_PAYABLE(ErrorType.BAD_REQUEST),

    // ── Secure viewer (Phase 5, D12) ─────────────────────────────────────────
    /** No ACTIVE entitlement for this buyer+product (startViewerSession, and the tile
     *  endpoint's D6 step 6). REST maps FORBIDDEN -> 403; GraphQL surfaces it as FORBIDDEN. */
    NOT_ENTITLED(ErrorType.FORBIDDEN),
    /** D6 step 5: another device now holds the active session. REST-only; GlobalRestExceptionHandler
     *  special-cases this to 409, since GraphQL's ErrorType has no CONFLICT equivalent. */
    VIEWER_SESSION_SUPERSEDED(ErrorType.FORBIDDEN),
    /** D6 step 5: the lease lapsed. REST-only; special-cased to 410 (see above). */
    VIEWER_SESSION_EXPIRED(ErrorType.FORBIDDEN),
    /** D6 steps 2-4: bad/expired/tampered/reused signature, or the URL's userId doesn't match
     *  the caller's JWT. Deliberately one error code for all of these — see TileUrlSigner. */
    SIGNED_URL_INVALID(ErrorType.FORBIDDEN),

    // ── Notifications (Phase 6, D7) ────────────────────────────────────────────
    /** The SSE stream's ticket is missing, unknown, or already consumed (single-use). REST-only —
     *  GlobalRestExceptionHandler's generic UNAUTHORIZED branch maps this to 401. */
    SSE_TICKET_INVALID(ErrorType.UNAUTHORIZED);

    private final ErrorType errorType;

    ErrorCode(ErrorType errorType) {
        this.errorType = errorType;
    }

    public ErrorType getErrorType() {
        return errorType;
    }
}
