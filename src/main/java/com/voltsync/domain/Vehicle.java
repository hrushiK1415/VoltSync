package com.voltsync.domain;

import java.util.Objects;

/**
 * Electric vehicle domain entity.
 */
public class Vehicle {
    private final String id;
    private final String tenantId;
    private final double batteryKWh;
    private double socPct;
    private final double consumptionKWhPerKm;
    private GeoLocation location;
    private String hubId;

    public Vehicle(String id, String tenantId, double batteryKWh, double socPct,
                   double consumptionKWhPerKm, GeoLocation location, String hubId) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
        if (batteryKWh <= 0.0) {
            throw new IllegalArgumentException("batteryKWh must be positive, got: " + batteryKWh);
        }
        this.batteryKWh = batteryKWh;
        if (socPct < 0.0 || socPct > 100.0) {
            throw new IllegalArgumentException("socPct must be in [0, 100], got: " + socPct);
        }
        this.socPct = socPct;
        if (consumptionKWhPerKm <= 0.0) {
            throw new IllegalArgumentException("consumptionKWhPerKm must be positive, got: " + consumptionKWhPerKm);
        }
        this.consumptionKWhPerKm = consumptionKWhPerKm;
        this.location = Objects.requireNonNull(location, "location must not be null");
        this.hubId = hubId;
    }

    public String getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public double getBatteryKWh() {
        return batteryKWh;
    }

    public double getSocPct() {
        return socPct;
    }

    public void setSocPct(double socPct) {
        if (socPct < 0.0 || socPct > 100.0) {
            throw new IllegalArgumentException("socPct must be in [0, 100], got: " + socPct);
        }
        this.socPct = socPct;
    }

    public double getConsumptionKWhPerKm() {
        return consumptionKWhPerKm;
    }

    public GeoLocation getLocation() {
        return location;
    }

    public void setLocation(GeoLocation location) {
        this.location = Objects.requireNonNull(location, "location must not be null");
    }

    public String getHubId() {
        return hubId;
    }

    public void setHubId(String hubId) {
        this.hubId = hubId;
    }

    /**
     * Calculates usable battery energy in kWh given current SoC.
     */
    public double getCurrentEnergyKWh() {
        return (socPct / 100.0) * batteryKWh;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Vehicle vehicle = (Vehicle) o;
        return Objects.equals(id, vehicle.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Vehicle{" +
                "id='" + id + '\'' +
                ", tenantId='" + tenantId + '\'' +
                ", batteryKWh=" + batteryKWh +
                ", socPct=" + socPct +
                ", consumptionKWhPerKm=" + consumptionKWhPerKm +
                ", location=" + location +
                ", hubId='" + hubId + '\'' +
                '}';
    }
}
