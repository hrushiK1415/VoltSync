package com.voltsync.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Objects;

/**
 * Event representing an active vehicle charging session at a specific charger stall.
 */
public class ChargeEvent extends Event {
    private final String hubId;
    private final String chargerId;
    private final double socFrom;
    private final double socTo;
    private final double cost;
    private final double energyAddedKWh;

    @JsonCreator
    public ChargeEvent(@JsonProperty("hubId") String hubId,
                       @JsonProperty("chargerId") String chargerId,
                       @JsonProperty("startTime") Instant startTime,
                       @JsonProperty("endTime") Instant endTime,
                       @JsonProperty("startLocation") GeoLocation startLocation,
                       @JsonProperty("socFrom") double socFrom,
                       @JsonProperty("socTo") double socTo,
                       @JsonProperty("cost") double cost,
                       @JsonProperty("energyAddedKWh") double energyAddedKWh,
                       @JsonProperty("socBefore") double socBefore,
                       @JsonProperty("socAfter") double socAfter) {
        super(EventType.CHARGE, startTime, endTime, startLocation, startLocation, socBefore, socAfter);
        this.hubId = Objects.requireNonNull(hubId, "hubId must not be null");
        this.chargerId = Objects.requireNonNull(chargerId, "chargerId must not be null");
        this.socFrom = socFrom;
        this.socTo = socTo;
        this.cost = cost;
        this.energyAddedKWh = energyAddedKWh;
    }

    public String getHubId() {
        return hubId;
    }

    public String getChargerId() {
        return chargerId;
    }

    public double getSocFrom() {
        return socFrom;
    }

    public double getSocTo() {
        return socTo;
    }

    public double getCost() {
        return cost;
    }

    public double getEnergyAddedKWh() {
        return energyAddedKWh;
    }

    @Override
    public double applySoc(double currentSoc, double batteryKWh) {
        // Charging increases SoC to socTo
        return Math.min(100.0, Math.max(currentSoc, socTo));
    }

    @Override
    public String toString() {
        return "ChargeEvent{" +
                "hubId='" + hubId + '\'' +
                ", chargerId='" + chargerId + '\'' +
                ", start=" + getStartTime() +
                ", end=" + getEndTime() +
                ", soc=" + socFrom + "%->" + socTo + "%" +
                ", cost=$" + String.format("%.2f", cost) +
                ", energy=" + String.format("%.2f", energyAddedKWh) + "kWh" +
                '}';
    }
}
