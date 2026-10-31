package com.voltsync.domain;

import java.util.Objects;

/**
 * Result of an incremental re-planning operation containing the updated Plan and diff.
 */
public record ReplanResult(Plan plan, ReplanDiff diff, ValidationReport validationReport) {
    public ReplanResult {
        Objects.requireNonNull(plan, "plan must not be null");
        Objects.requireNonNull(diff, "diff must not be null");
        Objects.requireNonNull(validationReport, "validationReport must not be null");
    }
}
