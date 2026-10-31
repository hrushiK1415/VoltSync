package com.voltsync.domain;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * Represents a daily recurring time of day window (e.g. 08:00 to 11:00) in UTC.
 */
public record TimeOfDayWindow(LocalTime start, LocalTime end) {

    public TimeOfDayWindow {
        Objects.requireNonNull(start, "start must not be null");
        Objects.requireNonNull(end, "end must not be null");
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("end (" + end + ") must be after start (" + start + ")");
        }
    }

    /**
     * Checks if a given time of day falls within this window (inclusive start, exclusive end).
     */
    public boolean contains(LocalTime time) {
        return !time.isBefore(start) && time.isBefore(end);
    }

    /**
     * Checks if an Instant falls within this window in UTC.
     */
    public boolean contains(Instant instant) {
        return contains(instant, ZoneOffset.UTC);
    }

    public boolean contains(Instant instant, ZoneId zoneId) {
        LocalTime time = instant.atZone(zoneId).toLocalTime();
        return contains(time);
    }
}
