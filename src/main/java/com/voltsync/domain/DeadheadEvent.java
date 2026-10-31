package com.voltsync.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Event representing an empty vehicle repositioning drive.
 */
public class DeadheadEvent extends Event {
    private final double distanceKm;
    private final double energyUsedKWh;
    private final DeadheadPurpose purpose;

    public DeadheadEvent(Instant startTime, Instant endTime,
                         GeoLocation startLocation, GeoLocation endLocation,
                         double distanceKm, double energyUsedKWh,
                         DeadheadPurpose purpose,
                         double socBefore, double socAfter) {
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
