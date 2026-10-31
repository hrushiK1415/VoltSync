package com.voltsync.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Electricity tariff with peak and off-peak energy rates.
 */
public class Tariff {
    private final List<TimeOfDayWindow> peakWindows;
    private final double peakRatePerKWh;
    private final double offPeakRatePerKWh;

    public Tariff(List<TimeOfDayWindow> peakWindows, double peakRatePerKWh, double offPeakRatePerKWh) {
        this.peakWindows = peakWindows != null ? new ArrayList<>(peakWindows) : new ArrayList<>();
        if (peakRatePerKWh < 0.0 || offPeakRatePerKWh < 0.0) {
            throw new IllegalArgumentException("Rates must be non-negative");
        }
        this.peakRatePerKWh = peakRatePerKWh;
        this.offPeakRatePerKWh = offPeakRatePerKWh;
    }

    public List<TimeOfDayWindow> getPeakWindows() {
        return Collections.unmodifiableList(peakWindows);
    }

    public double getPeakRatePerKWh() {
        return peakRatePerKWh;
    }

    public double getOffPeakRatePerKWh() {
        return offPeakRatePerKWh;
    }

    /**
     * Checks if a given timestamp falls into any defined peak window.
     */
    public boolean isPeak(Instant instant) {
        for (TimeOfDayWindow window : peakWindows) {
            if (window.contains(instant, ZoneOffset.UTC)) {
                return true;
            }
        }
        return false;
    }

    public double getRateAt(Instant instant) {
        return isPeak(instant) ? peakRatePerKWh : offPeakRatePerKWh;
    }

    /**
     * Computes the charging cost for energy charged continuously over [start, end].
     * Weighting is based on the fraction of time spent in peak vs off-peak windows.
     */
    public double calculateCost(Instant start, Instant end, double energyKWh) {
        if (energyKWh <= 0.0) {
            return 0.0;
        }
        if (start.equals(end) || start.isAfter(end)) {
            return energyKWh * getRateAt(start);
        }

        long totalSeconds = Duration.between(start, end).toSeconds();
        if (totalSeconds <= 0) {
            return energyKWh * getRateAt(start);
        }

        // Sample in 1-minute steps or analytically calculate overlap
        long stepSeconds = Math.max(1, Math.min(60, totalSeconds / 60));
        long peakSeconds = 0;

        Instant cur = start;
        while (cur.isBefore(end)) {
            Instant next = cur.plusSeconds(stepSeconds);
            if (next.isAfter(end)) {
                next = end;
            }
            long step = Duration.between(cur, next).toSeconds();
            // Check midpoint of step
            Instant midpoint = cur.plusSeconds(step / 2);
            if (isPeak(midpoint)) {
                peakSeconds += step;
            }
            cur = next;
        }

        double peakFraction = (double) peakSeconds / totalSeconds;
        double effectiveRate = (peakFraction * peakRatePerKWh) + ((1.0 - peakFraction) * offPeakRatePerKWh);
        return energyKWh * effectiveRate;
    }

    /**
     * Factory for standard municipal dual-tier tariff (e.g. 08:00-11:00 and 17:00-20:00 peak).
     */
    public static Tariff defaultTariff() {
        List<TimeOfDayWindow> windows = List.of(
                new TimeOfDayWindow(java.time.LocalTime.of(8, 0), java.time.LocalTime.of(11, 0)),
                new TimeOfDayWindow(java.time.LocalTime.of(17, 0), java.time.LocalTime.of(20, 0))
        );
        return new Tariff(windows, 0.35, 0.12);
    }
}
