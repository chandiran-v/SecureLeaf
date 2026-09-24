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
    ORDER_NOT_PAYABLE(ErrorType.BAD_REQUEST);

    private final ErrorType errorType;

    ErrorCode(ErrorType errorType) {
        this.errorType = errorType;
    }

    public ErrorType getErrorType() {
        return errorType;
    }
}
