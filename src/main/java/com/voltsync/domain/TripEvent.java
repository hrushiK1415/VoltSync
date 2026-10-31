package com.voltsync.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Event representing an active passenger-carrying trip.
 */
public class TripEvent extends Event {
    private final String tripId;
    private final double distanceKm;
    private final double energyUsedKWh;

    public TripEvent(String tripId, Instant startTime, Instant endTime,
                     GeoLocation startLocation, GeoLocation endLocation,
                     double distanceKm, double energyUsedKWh,
                     double socBefore, double socAfter) {
        super(EventType.TRIP, startTime, endTime, startLocation, endLocation, socBefore, socAfter);
        this.tripId = Objects.requireNonNull(tripId, "tripId must not be null");
        this.distanceKm = distanceKm;
        this.energyUsedKWh = energyUsedKWh;
    }

    public String getTripId() {
        return tripId;
    }

    public double getDistanceKm() {
        return distanceKm;
    }

    public double getEnergyUsedKWh() {
        return energyUsedKWh;
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
        return "TripEvent{" +
                "tripId='" + tripId + '\'' +
                ", start=" + getStartTime() +
                ", end=" + getEndTime() +
                ", distanceKm=" + distanceKm +
                ", soc=" + getSocBefore() + "%->" + getSocAfter() + "%" +
                '}';
    }
}
