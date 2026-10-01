package com.chitthi.api;

import com.chitthi.exception.DailyWordCapExceededException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(DailyWordCapExceededException.class)
    public ResponseEntity<Map<String, Object>> handleDailyWordCapExceeded(DailyWordCapExceededException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of(
                "error", "DAILY_WORD_CAP_EXCEEDED",
                "message", ex.getMessage(),
                "ownerId", ex.getOwnerId(),
                "currentWords", ex.getCurrentWords(),
                "cap", ex.getCap(),
                "timestamp", Instant.now().toString()
        ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "error", "BAD_REQUEST",
                "message", ex.getMessage(),
                "timestamp", Instant.now().toString()
        ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationErrors(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .findFirst()
                .orElse("Validation failed");

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "error", "VALIDATION_FAILED",
                "message", message,
                "timestamp", Instant.now().toString()
        ));
    }

    @ExceptionHandler(com.chitthi.exception.DocumentNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleDocumentNotFound(com.chitthi.exception.DocumentNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "error", "DOCUMENT_NOT_FOUND",
                "message", ex.getMessage(),
                "documentId", ex.getDocumentId().toString(),
                "timestamp", Instant.now().toString()
        ));
    }

    @ExceptionHandler(com.chitthi.exception.UnauthorizedDocumentAccessException.class)
    public ResponseEntity<Map<String, Object>> handleUnauthorizedAccess(com.chitthi.exception.UnauthorizedDocumentAccessException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "error", "FORBIDDEN",
                "message", ex.getMessage(),
                "documentId", ex.getDocumentId().toString(),
                "ownerId", ex.getRequestingOwnerId(),
                "timestamp", Instant.now().toString()
        ));
    }

    @ExceptionHandler(com.chitthi.exception.RateLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleRateLimitExceeded(com.chitthi.exception.RateLimitExceededException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(ex.getRetryAfterSeconds()))
                .body(Map.of(
                        "error", "RATE_LIMIT_EXCEEDED",
                        "message", ex.getMessage(),
                        "retryAfterSeconds", ex.getRetryAfterSeconds(),
                        "timestamp", Instant.now().toString()
                ));
    }
}
