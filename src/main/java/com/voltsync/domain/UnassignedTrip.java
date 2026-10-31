package com.voltsync.domain;

import java.util.Objects;

/**
 * Details of an unassigned trip and the root cause reason.
 */
public record UnassignedTrip(String tripId, UnassignedReason reason, String details) {

    public UnassignedTrip {
        Objects.requireNonNull(tripId, "tripId must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
    }

    public UnassignedTrip(String tripId, UnassignedReason reason) {
        this(tripId, reason, null);
    }
}
