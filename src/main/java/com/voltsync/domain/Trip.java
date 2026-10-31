package com.voltsync.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Scheduled customer passenger trip.
 */
public class Trip {
    private final String id;
    private final String tenantId;
    private final Instant startTime;
    private final GeoLocation startLoc;
    private final GeoLocation endLoc;
    private final double distanceKm;
    private final Instant latestFinish;

    public Trip(String id, String tenantId, Instant startTime, GeoLocation startLoc,
                GeoLocation endLoc, double distanceKm, Instant latestFinish) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
        this.startTime = Objects.requireNonNull(startTime, "startTime must not be null");
        this.startLoc = Objects.requireNonNull(startLoc, "startLoc must not be null");
        this.endLoc = Objects.requireNonNull(endLoc, "endLoc must not be null");
        this.distanceKm = distanceKm > 0.0 ? distanceKm : startLoc.drivingDistanceKm(endLoc);
        this.latestFinish = Objects.requireNonNull(latestFinish, "latestFinish must not be null");

        if (latestFinish.isBefore(startTime)) {
            throw new IllegalArgumentException("latestFinish (" + latestFinish + ") cannot be before startTime (" + startTime + ")");
        }
    }

    public String getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public GeoLocation getStartLoc() {
        return startLoc;
    }

    public GeoLocation getEndLoc() {
        return endLoc;
    }

    public double getDistanceKm() {
        return distanceKm;
    }

    public Instant getLatestFinish() {
        return latestFinish;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Trip trip = (Trip) o;
        return Objects.equals(id, trip.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Trip{" +
                "id='" + id + '\'' +
                ", tenantId='" + tenantId + '\'' +
                ", startTime=" + startTime +
                ", startLoc=" + startLoc +
                ", endLoc=" + endLoc +
                ", distanceKm=" + distanceKm +
                ", latestFinish=" + latestFinish +
                '}';
    }
}
