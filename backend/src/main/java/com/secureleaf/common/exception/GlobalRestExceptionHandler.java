package com.secureleaf.common.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Global exception handler for REST controllers (@RestController — file uploads and the
 * preview endpoint). Distinct from {@link GlobalGraphQlExceptionHandler}, which only
 * intercepts exceptions thrown from GraphQL data fetchers; without this class, a
 * ResourceNotFoundException thrown from {@code PreviewController} would fall through to
 * Spring Boot's default error handling and come back as a 500, not the 404 the DRM
 * boundary test (PreviewControllerIT) requires.
 *
 * Body shape ({@code {"message": ...}}) matches what DocumentUploadController's callers
 * already expect (see DocumentUploadIT's jsonPath("$.message") assertions).
 */
@RestControllerAdvice
@Slf4j
public class GlobalRestExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", ex.getMessage()));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, String>> handleBusiness(BusinessException ex) {
        // VIEWER_SESSION_SUPERSEDED/EXPIRED (D6 step 5) need 409/410, which graphql-java's
        // ErrorType has no equivalent for (it only has BAD_REQUEST/UNAUTHORIZED/FORBIDDEN/
        // NOT_FOUND/INTERNAL_ERROR). These two codes are REST-only, so special-case them here
        // rather than stretch ErrorType with values GraphQL would never use.
        HttpStatus status = switch (ex.getErrorCode()) {
            case VIEWER_SESSION_SUPERSEDED -> HttpStatus.CONFLICT;
            case VIEWER_SESSION_EXPIRED -> HttpStatus.GONE;
            default -> switch (ex.getErrorCode().getErrorType()) {
                case NOT_FOUND -> HttpStatus.NOT_FOUND;
                case FORBIDDEN -> HttpStatus.FORBIDDEN;
                case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
                default -> HttpStatus.BAD_REQUEST;
            };
        };
        return ResponseEntity.status(status).body(Map.of("message", ex.getMessage(), "code", ex.getErrorCode().name()));
    }
}
