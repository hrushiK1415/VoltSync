package com.voltsync.domain;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.time.Instant;
import java.util.Objects;

/**
 * Abstract polymorphic timeline event experienced by an electric cab.
 */
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "type"
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = TripEvent.class, name = "TRIP"),
        @JsonSubTypes.Type(value = DeadheadEvent.class, name = "DEADHEAD"),
        @JsonSubTypes.Type(value = ChargeEvent.class, name = "CHARGE")
})
public abstract class Event {
    private final EventType type;
    private final Instant startTime;
    private final Instant endTime;
    private final GeoLocation startLocation;
    private final GeoLocation endLocation;
    private double socBefore;
    private double socAfter;

    protected Event(EventType type, Instant startTime, Instant endTime,
                    GeoLocation startLocation, GeoLocation endLocation,
                    double socBefore, double socAfter) {
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.startTime = Objects.requireNonNull(startTime, "startTime must not be null");
        this.endTime = Objects.requireNonNull(endTime, "endTime must not be null");
        if (endTime.isBefore(startTime)) {
            throw new IllegalArgumentException("endTime (" + endTime + ") cannot be before startTime (" + startTime + ")");
        }
        this.startLocation = Objects.requireNonNull(startLocation, "startLocation must not be null");
        this.endLocation = Objects.requireNonNull(endLocation, "endLocation must not be null");
        this.socBefore = socBefore;
        this.socAfter = socAfter;
    }

    public EventType getType() {
        return type;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public GeoLocation getStartLocation() {
        return startLocation;
    }

    public GeoLocation getEndLocation() {
        return endLocation;
    }

    public double getSocBefore() {
        return socBefore;
    }

    public void setSocBefore(double socBefore) {
        this.socBefore = socBefore;
    }

    public double getSocAfter() {
        return socAfter;
    }

    public void setSocAfter(double socAfter) {
        this.socAfter = socAfter;
    }

    /**
     * Polymorphically applies this event to a vehicle's State of Charge.
     *
     * @param currentSoc current SoC in percent
     * @param batteryKWh vehicle battery capacity in kWh
     * @return resulting SoC in percent
     */
    public abstract double applySoc(double currentSoc, double batteryKWh);
}
