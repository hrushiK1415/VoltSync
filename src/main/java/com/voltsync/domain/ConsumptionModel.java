package com.voltsync.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * Energy consumption and driving physics model.
 * Incorporates base consumption per km, temporal traffic multipliers, and AC load.
 */
public class ConsumptionModel {

    public static final double DEFAULT_BASE_SPEED_KM_H = 25.0;
    public static final double DEFAULT_BASE_KWH_PER_KM = 0.18;
    public static final double DEFAULT_AC_MULTIPLIER = 1.10;
    public static final double DEFAULT_PEAK_TRAFFIC_MULTIPLIER = 1.30;

    private final double baseKWhPerKm;
    private final double acMultiplier;
    private final double baseSpeedKmH;
    private final double peakTrafficMultiplier;

    public ConsumptionModel(double baseKWhPerKm, double acMultiplier, double baseSpeedKmH, double peakTrafficMultiplier) {
        if (baseKWhPerKm <= 0.0) throw new IllegalArgumentException("baseKWhPerKm must be positive");
        if (acMultiplier <= 0.0) throw new IllegalArgumentException("acMultiplier must be positive");
        if (baseSpeedKmH <= 0.0) throw new IllegalArgumentException("baseSpeedKmH must be positive");
        if (peakTrafficMultiplier < 1.0) throw new IllegalArgumentException("peakTrafficMultiplier must be >= 1.0");

        this.baseKWhPerKm = baseKWhPerKm;
        this.acMultiplier = acMultiplier;
        this.baseSpeedKmH = baseSpeedKmH;
        this.peakTrafficMultiplier = peakTrafficMultiplier;
    }

    public static ConsumptionModel defaultModel() {
        return new ConsumptionModel(DEFAULT_BASE_KWH_PER_KM, DEFAULT_AC_MULTIPLIER, DEFAULT_BASE_SPEED_KM_H, DEFAULT_PEAK_TRAFFIC_MULTIPLIER);
    }

    public double getBaseKWhPerKm() {
        return baseKWhPerKm;
    }

    public double getAcMultiplier() {
        return acMultiplier;
    }

    public double getBaseSpeedKmH() {
        return baseSpeedKmH;
    }

    public double getPeakTrafficMultiplier() {
        return peakTrafficMultiplier;
    }

    /**
     * Temporal traffic multiplier: elevated during peak commute hours (08:00-11:00 and 17:00-20:00 UTC).
     */
    public double trafficMultiplier(Instant time) {
        LocalTime lt = time.atZone(ZoneOffset.UTC).toLocalTime();
        boolean isMorningPeak = !lt.isBefore(LocalTime.of(8, 0)) && lt.isBefore(LocalTime.of(11, 0));
        boolean isEveningPeak = !lt.isBefore(LocalTime.of(17, 0)) && lt.isBefore(LocalTime.of(20, 0));

        return (isMorningPeak || isEveningPeak) ? peakTrafficMultiplier : 1.0;
    }

    /**
     * Effective vehicle speed considering traffic congestion.
     */
    public double effectiveSpeedKmH(Instant time) {
        return baseSpeedKmH / trafficMultiplier(time);
    }

    /**
     * Estimates travel duration for a given road distance starting at a specific time.
     */
    public Duration travelDuration(double distanceKm, Instant startTime) {
        if (distanceKm <= 0.0) {
            return Duration.ZERO;
        }
        double speed = effectiveSpeedKmH(startTime);
        double hours = distanceKm / speed;
        long seconds = Math.round(hours * 3600.0);
        return Duration.ofSeconds(Math.max(1, seconds));
    }

    /**
     * Total energy consumed (kWh) for a given road distance.
     */
    public double calculateEnergy(double distanceKm, Instant startTime) {
        if (distanceKm <= 0.0) {
            return 0.0;
        }
        return distanceKm * baseKWhPerKm * trafficMultiplier(startTime) * acMultiplier;
    }

    /**
     * Calculates the drop in State of Charge (%) for a vehicle driving distanceKm.
     */
    public double calculateSocDrop(double distanceKm, Instant startTime, double batteryKWh) {
        if (batteryKWh <= 0.0 || distanceKm <= 0.0) {
            return 0.0;
        }
        double energyKWh = calculateEnergy(distanceKm, startTime);
        return (energyKWh / batteryKWh) * 100.0;
    }
}
