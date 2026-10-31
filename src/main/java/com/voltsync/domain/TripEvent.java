package com.voltsync.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Objects;

/**
 * Event representing an active passenger-carrying trip.
 */
public class TripEvent extends Event {
    private final String tripId;
    private final double distanceKm;
    private final double energyUsedKWh;

    @JsonCreator
    public TripEvent(@JsonProperty("tripId") String tripId,
                     @JsonProperty("startTime") Instant startTime,
                     @JsonProperty("endTime") Instant endTime,
                     @JsonProperty("startLocation") GeoLocation startLocation,
                     @JsonProperty("endLocation") GeoLocation endLocation,
                     @JsonProperty("distanceKm") double distanceKm,
                     @JsonProperty("energyUsedKWh") double energyUsedKWh,
                     @JsonProperty("socBefore") double socBefore,
                     @JsonProperty("socAfter") double socAfter) {
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
