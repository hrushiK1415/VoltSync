package com.voltsync.common;

import java.time.Instant;

/**
 * Standard sanitized error response returned by @ControllerAdvice.
 * Omits internal stack traces to protect system internals.
 */
public record ErrorResponse(
        String code,
        String message,
        String traceId,
        Instant timestamp
) {
    public ErrorResponse(String code, String message, String traceId) {
        this(code, message, traceId, Instant.now());
    }
}
