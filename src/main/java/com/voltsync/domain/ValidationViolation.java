package com.voltsync.domain;

import java.time.Instant;

/**
 * Details of a constraint violation flagged by the independent validator.
 */
public record ValidationViolation(
        String code,
        String message,
        String entityId,
        Instant timestamp
) {
    public ValidationViolation(String code, String message, String entityId) {
        this(code, message, entityId, null);
    }
}
