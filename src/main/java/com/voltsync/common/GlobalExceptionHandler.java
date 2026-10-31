package com.voltsync.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.UUID;

/**
 * Centralized exception handler ensuring all API errors return sanitized JSON with traceId.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ErrorResponse> handleAppException(AppException ex) {
        String traceId = getOrCreateTraceId();
        log.warn("Application error [code={}, traceId={}]: {}", ex.getErrorCode().getCode(), traceId, ex.getMessage());

        ErrorResponse response = new ErrorResponse(ex.getErrorCode().getCode(), ex.getMessage(), traceId);
        return ResponseEntity.status(ex.getErrorCode().getHttpStatus()).body(response);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException ex) {
        String traceId = getOrCreateTraceId();
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .findFirst()
                .orElse("Validation failed");

        log.warn("Validation error [traceId={}]: {}", traceId, msg);
        ErrorResponse response = new ErrorResponse(ErrorCode.INVALID_INPUT.getCode(), msg, traceId);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(org.springframework.dao.OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(org.springframework.dao.OptimisticLockingFailureException ex) {
        String traceId = getOrCreateTraceId();
        log.warn("Optimistic lock conflict [traceId={}]: {}", traceId, ex.getMessage());

        ErrorResponse response = new ErrorResponse(
                ErrorCode.CONCURRENT_MODIFICATION.getCode(),
                "Plan version conflict: another re-plan was applied concurrently. Please re-query the latest plan.",
                traceId
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneralException(Exception ex) {
        String traceId = getOrCreateTraceId();
        log.error("Unhandled internal server error [traceId={}]", traceId, ex);

        ErrorResponse response = new ErrorResponse(
                ErrorCode.INTERNAL_ERROR.getCode(),
                "An unexpected internal error occurred. Please contact support with trace ID.",
                traceId
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    private String getOrCreateTraceId() {
        String traceId = MDC.get("traceId");
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
            MDC.put("traceId", traceId);
        }
        return traceId;
    }
}
