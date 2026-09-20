package com.secureleaf.common.exception;

import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.graphql.data.method.annotation.GraphQlExceptionHandler;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ControllerAdvice;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Global exception handler for GraphQL resolvers.
 * Maps application exceptions to structured GraphQL errors with proper classification.
 */
@ControllerAdvice
@Slf4j
public class GlobalGraphQlExceptionHandler {

    @GraphQlExceptionHandler(ResourceNotFoundException.class)
    public GraphQLError handleNotFound(ResourceNotFoundException ex, DataFetchingEnvironment env) {
        return GraphqlErrorBuilder.newError(env)
                .message(ex.getMessage())
                .errorType(ErrorType.NOT_FOUND)
                .extensions(Map.of("code", "NOT_FOUND"))
                .build();
    }

    @GraphQlExceptionHandler(DuplicateResourceException.class)
    public GraphQLError handleDuplicate(DuplicateResourceException ex, DataFetchingEnvironment env) {
        return GraphqlErrorBuilder.newError(env)
                .message(ex.getMessage())
                .errorType(ErrorType.BAD_REQUEST)
                .extensions(Map.of("code", "DUPLICATE_RESOURCE"))
                .build();
    }

    @GraphQlExceptionHandler(BusinessException.class)
    public GraphQLError handleBusiness(BusinessException ex, DataFetchingEnvironment env) {
        return GraphqlErrorBuilder.newError(env)
                .message(ex.getMessage())
                .errorType(ex.getErrorCode().getErrorType())
                .extensions(Map.of("code", ex.getErrorCode().name()))
                .build();
    }

    @GraphQlExceptionHandler(ConstraintViolationException.class)
    public GraphQLError handleConstraintViolation(ConstraintViolationException ex, DataFetchingEnvironment env) {
        Map<String, String> fieldErrors = ex.getConstraintViolations().stream()
                .collect(Collectors.toMap(
                        v -> v.getPropertyPath().toString(),
                        v -> v.getMessage(),
                        (msg1, msg2) -> msg1 + ", " + msg2
                ));

        return GraphqlErrorBuilder.newError(env)
                .message("Validation failed")
                .errorType(ErrorType.BAD_REQUEST)
                .extensions(Map.of("code", "VALIDATION_FAILED", "fieldErrors", fieldErrors))
                .build();
    }

    @GraphQlExceptionHandler(AccessDeniedException.class)
    public GraphQLError handleAccessDenied(AccessDeniedException ex, DataFetchingEnvironment env) {
        log.warn("Access denied for user on path: {}", env.getExecutionStepInfo().getPath());
        return GraphqlErrorBuilder.newError(env)
                .message("Access denied")
                .errorType(ErrorType.FORBIDDEN)
                .extensions(Map.of("code", "ACCESS_DENIED"))
                .build();
    }

    @GraphQlExceptionHandler(AuthenticationException.class)
    public GraphQLError handleAuthenticationException(AuthenticationException ex, DataFetchingEnvironment env) {
        return GraphqlErrorBuilder.newError(env)
                .message("Authentication required")
                .errorType(ErrorType.UNAUTHORIZED)
                .extensions(Map.of("code", "UNAUTHORIZED"))
                .build();
    }

    @GraphQlExceptionHandler(Throwable.class)
    public GraphQLError handleThrowable(Throwable ex, DataFetchingEnvironment env) {
        String errorId = UUID.randomUUID().toString();
        log.error("Unhandled GraphQL error [ErrorId: {}]: {}", errorId, ex.getMessage(), ex);

        return GraphqlErrorBuilder.newError(env)
                .message("An unexpected error occurred. Please contact support with Error ID: " + errorId)
                .errorType(ErrorType.INTERNAL_ERROR)
                .extensions(Map.of("code", "INTERNAL_SERVER_ERROR", "errorId", errorId))
                .build();
    }
}
