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
    FILE_TOO_LARGE(ErrorType.BAD_REQUEST);

    private final ErrorType errorType;

    ErrorCode(ErrorType errorType) {
        this.errorType = errorType;
    }

    public ErrorType getErrorType() {
        return errorType;
    }
}
