package com.secureleaf.common.exception;

/**
 * Thrown when a unique-constraint violation would occur
 * (e.g. registering with an email that already exists).
 */
public class DuplicateResourceException extends BusinessException {

    public DuplicateResourceException(String resource, String field, Object value) {
        super(ErrorCode.RESOURCE_ALREADY_EXISTS, resource + " already exists with " + field + ": " + value);
    }
}
