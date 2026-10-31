package com.voltsync.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Objects;

/**
 * Event representing an empty vehicle repositioning drive.
 */
public class DeadheadEvent extends Event {
    private final double distanceKm;
    private final double energyUsedKWh;
    private final DeadheadPurpose purpose;

    @JsonCreator
    public DeadheadEvent(@JsonProperty("startTime") Instant startTime,
                         @JsonProperty("endTime") Instant endTime,
                         @JsonProperty("startLocation") GeoLocation startLocation,
                         @JsonProperty("endLocation") GeoLocation endLocation,
                         @JsonProperty("distanceKm") double distanceKm,
                         @JsonProperty("energyUsedKWh") double energyUsedKWh,
                         @JsonProperty("purpose") DeadheadPurpose purpose,
                         @JsonProperty("socBefore") double socBefore,
                         @JsonProperty("socAfter") double socAfter) {
        super(EventType.DEADHEAD, startTime, endTime, startLocation, endLocation, socBefore, socAfter);
        this.distanceKm = distanceKm;
        this.energyUsedKWh = energyUsedKWh;
        this.purpose = Objects.requireNonNull(purpose, "purpose must not be null");
    }

    public double getDistanceKm() {
        return distanceKm;
    }

    public double getEnergyUsedKWh() {
        return energyUsedKWh;
    }

    public DeadheadPurpose getPurpose() {
        return purpose;
    }

    @Override
    public double applySoc(double currentSoc, double batteryKWh) {
        if (batteryKWh <= 0.0) {
            return currentSoc;
        }
        double socDrop = (energyUsedKWh / batteryKWh) * 100.0;
        return Math.max(0.0, currentSoc - socDrop);
    }

    @Override
    public String toString() {
        return "DeadheadEvent{" +
                "purpose=" + purpose +
                ", start=" + getStartTime() +
                ", end=" + getEndTime() +
                ", distanceKm=" + distanceKm +
                ", soc=" + getSocBefore() + "%->" + getSocAfter() + "%" +
                '}';
    }
}
