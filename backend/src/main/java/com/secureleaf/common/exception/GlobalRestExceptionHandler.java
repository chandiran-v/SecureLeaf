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
        HttpStatus status = switch (ex.getErrorCode().getErrorType()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(Map.of("message", ex.getMessage()));
    }
}
