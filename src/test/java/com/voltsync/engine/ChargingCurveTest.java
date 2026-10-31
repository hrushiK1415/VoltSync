package com.voltsync.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class ChargingCurveTest {

    private static final double BATTERY_KWH = 60.0;
    private static final double CHARGER_KW = 50.0;

    @Test
    @DisplayName("Fast charging 0-80% charges at full nominal power")
    void fastChargingSegment() {
        // Charge from 20% to 50% (30% of 60 kWh = 18 kWh)
        // At 50 kW, 18 / 50 = 0.36 hours = 1296 seconds
        Duration duration = ChargingCurve.timeToCharge(20.0, 50.0, BATTERY_KWH, CHARGER_KW);
        assertThat(duration.toSeconds()).isEqualTo(1296);

        // Check inverse chargeForDuration
        double finalSoc = ChargingCurve.chargeForDuration(20.0, duration, BATTERY_KWH, CHARGER_KW);
        assertThat(finalSoc).isCloseTo(50.0, within(0.01));
    }

    @Test
    @DisplayName("Taper charging 80-100% charges at 30% of nominal power")
    void slowChargingSegment() {
        // Charge from 80% to 90% (10% of 60 kWh = 6 kWh)
        // At 15 kW (50 kW * 0.3), 6 / 15 = 0.40 hours = 1440 seconds
        Duration duration = ChargingCurve.timeToCharge(80.0, 90.0, BATTERY_KWH, CHARGER_KW);
        assertThat(duration.toSeconds()).isEqualTo(1440);

        // Check inverse
        double finalSoc = ChargingCurve.chargeForDuration(80.0, duration, BATTERY_KWH, CHARGER_KW);
        assertThat(finalSoc).isCloseTo(90.0, within(0.01));
    }

    @Test
    @DisplayName("Charging spanning the 80% knee correctly integrates both pieces")
    void spanningKneeSegment() {
        // From 70% to 90%:
        // 70% -> 80%: 10% = 6 kWh at 50 kW = 0.12 h = 432 s
        // 80% -> 90%: 10% = 6 kWh at 15 kW = 0.40 h = 1440 s
        // Total = 1872 s
        Duration duration = ChargingCurve.timeToCharge(70.0, 90.0, BATTERY_KWH, CHARGER_KW);
        assertThat(duration.toSeconds()).isEqualTo(1872);

        double finalSoc = ChargingCurve.chargeForDuration(70.0, duration, BATTERY_KWH, CHARGER_KW);
        assertThat(finalSoc).isCloseTo(90.0, within(0.01));
    }

    @Test
    @DisplayName("Charging beyond 100% caps at 100%")
    void cappingAtHundredPercent() {
        Duration veryLong = Duration.ofHours(10);
        double finalSoc = ChargingCurve.chargeForDuration(50.0, veryLong, BATTERY_KWH, CHARGER_KW);
        assertThat(finalSoc).isEqualTo(100.0);
    }

    @Test
    @DisplayName("Zero or negative delta returns ZERO duration")
    void zeroDeltaReturnsZero() {
        assertThat(ChargingCurve.timeToCharge(50.0, 50.0, BATTERY_KWH, CHARGER_KW)).isEqualTo(Duration.ZERO);
        assertThat(ChargingCurve.timeToCharge(60.0, 40.0, BATTERY_KWH, CHARGER_KW)).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("Energy added calculation is accurate")
    void energyAddedCalculation() {
        // 20% of 60 kWh = 12 kWh
        double energy = ChargingCurve.energyAddedKWh(30.0, 50.0, BATTERY_KWH);
        assertThat(energy).isCloseTo(12.0, within(0.001));
    }

    @Test
    @DisplayName("Invalid parameters throw IllegalArgumentException")
    void invalidParameters() {
        assertThatThrownBy(() -> ChargingCurve.timeToCharge(10.0, 20.0, -10.0, 50.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChargingCurve.timeToCharge(10.0, 20.0, 60.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
