package com.voltsync.engine;

import java.time.Duration;

/**
 * Two-part piecewise linear charging curve model:
 * - 0% to 80%: charges at nominal powerKW (full rate).
 * - 80% to 100%: charges at powerKW * 0.3 (slow taper rate).
 *
 * Integrates analytical solutions for time-to-charge and final-state-of-charge.
 */
public final class ChargingCurve {

    public static final double SOC_KNEE_PCT = 80.0;
    public static final double TAPER_POWER_FACTOR = 0.30;

    private ChargingCurve() {
    }

    /**
     * Analytically computes the exact duration required to charge from fromSoc to toSoc.
     *
     * @param fromSoc    initial SoC in percent [0, 100]
     * @param toSoc      target SoC in percent [fromSoc, 100]
     * @param batteryKWh battery total capacity in kWh
     * @param powerKW    charger nominal power rating in kW
     * @return Duration required for charging
     */
    public static Duration timeToCharge(double fromSoc, double toSoc, double batteryKWh, double powerKW) {
        if (toSoc <= fromSoc) {
            return Duration.ZERO;
        }
        if (batteryKWh <= 0.0) {
            throw new IllegalArgumentException("batteryKWh must be positive, got: " + batteryKWh);
        }
        if (powerKW <= 0.0) {
            throw new IllegalArgumentException("powerKW must be positive, got: " + powerKW);
        }

        double clampedFrom = Math.max(0.0, Math.min(100.0, fromSoc));
        double clampedTo = Math.max(0.0, Math.min(100.0, toSoc));

        double powerFast = powerKW;
        double powerSlow = powerKW * TAPER_POWER_FACTOR;

        double hoursFast = 0.0;
        double hoursSlow = 0.0;

        if (clampedFrom < SOC_KNEE_PCT) {
            double fastTo = Math.min(clampedTo, SOC_KNEE_PCT);
            double deltaSocFast = fastTo - clampedFrom;
            hoursFast = (deltaSocFast * batteryKWh) / (100.0 * powerFast);
        }

        if (clampedTo > SOC_KNEE_PCT) {
            double slowFrom = Math.max(clampedFrom, SOC_KNEE_PCT);
            double deltaSocSlow = clampedTo - slowFrom;
            hoursSlow = (deltaSocSlow * batteryKWh) / (100.0 * powerSlow);
        }

        double totalSeconds = (hoursFast + hoursSlow) * 3600.0;
        return Duration.ofSeconds(Math.round(totalSeconds));
    }

    /**
     * Analytically computes the final SoC after charging for a given duration.
     *
     * @param fromSoc    starting SoC in percent [0, 100]
     * @param duration   charging duration
     * @param batteryKWh battery total capacity in kWh
     * @param powerKW    charger nominal power rating in kW
     * @return resulting SoC in percent [fromSoc, 100]
     */
    public static double chargeForDuration(double fromSoc, Duration duration, double batteryKWh, double powerKW) {
        if (duration.isZero() || duration.isNegative() || fromSoc >= 100.0) {
            return Math.min(100.0, Math.max(0.0, fromSoc));
        }
        if (batteryKWh <= 0.0) {
            throw new IllegalArgumentException("batteryKWh must be positive, got: " + batteryKWh);
        }
        if (powerKW <= 0.0) {
            throw new IllegalArgumentException("powerKW must be positive, got: " + powerKW);
        }

        double currentSoc = Math.max(0.0, Math.min(100.0, fromSoc));
        double remainingHours = duration.toMillis() / 3_600_000.0;

        double powerFast = powerKW;
        double powerSlow = powerKW * TAPER_POWER_FACTOR;

        // Fast charging up to knee point (80%)
        if (currentSoc < SOC_KNEE_PCT) {
            double hoursToKnee = ((SOC_KNEE_PCT - currentSoc) * batteryKWh) / (100.0 * powerFast);
            if (remainingHours <= hoursToKnee) {
                double deltaSoc = (remainingHours * 100.0 * powerFast) / batteryKWh;
                return Math.min(SOC_KNEE_PCT, currentSoc + deltaSoc);
            }
            currentSoc = SOC_KNEE_PCT;
            remainingHours -= hoursToKnee;
        }

        // Slow taper charging above knee point (80% to 100%)
        if (remainingHours > 0.0 && currentSoc < 100.0) {
            double deltaSoc = (remainingHours * 100.0 * powerSlow) / batteryKWh;
            currentSoc = Math.min(100.0, currentSoc + deltaSoc);
        }

        return currentSoc;
    }

    /**
     * Calculates the battery energy in kWh added when changing from fromSoc to toSoc.
     */
    public static double energyAddedKWh(double fromSoc, double toSoc, double batteryKWh) {
        if (toSoc <= fromSoc) {
            return 0.0;
        }
        double deltaSoc = Math.max(0.0, toSoc - fromSoc);
        return (deltaSoc / 100.0) * batteryKWh;
    }
}
