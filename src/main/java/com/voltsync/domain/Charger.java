package com.voltsync.domain;

import java.util.Objects;

/**
 * Represents an EV charging point at a hub.
 */
public class Charger {
    private final String id;
    private final String hubId;
    private final double powerKW;
    private ChargerStatus status;

    public Charger(String id, String hubId, double powerKW, ChargerStatus status) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.hubId = Objects.requireNonNull(hubId, "hubId must not be null");
        if (powerKW <= 0.0) {
            throw new IllegalArgumentException("powerKW must be positive, got: " + powerKW);
        }
        this.powerKW = powerKW;
        this.status = Objects.requireNonNull(status, "status must not be null");
    }

    public String getId() {
        return id;
    }

    public String getHubId() {
        return hubId;
    }

    public double getPowerKW() {
        return powerKW;
    }

    public ChargerStatus getStatus() {
        return status;
    }

    public void setStatus(ChargerStatus status) {
        this.status = Objects.requireNonNull(status, "status must not be null");
    }

    public boolean isUp() {
        return status == ChargerStatus.UP;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Charger charger = (Charger) o;
        return Objects.equals(id, charger.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Charger{" +
                "id='" + id + '\'' +
                ", hubId='" + hubId + '\'' +
                ", powerKW=" + powerKW +
                ", status=" + status +
                '}';
    }
}
