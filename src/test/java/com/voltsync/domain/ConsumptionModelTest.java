package com.voltsync.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ConsumptionModelTest {

    private final ConsumptionModel model = ConsumptionModel.defaultModel();

    @Test
    @DisplayName("Traffic multiplier is 1.3 during morning and evening peak hours, 1.0 off-peak")
    void trafficMultiplierPeakAndOffPeak() {
        // 09:00 UTC -> morning peak
        Instant morningPeak = Instant.parse("2026-10-31T09:00:00Z");
        assertThat(model.trafficMultiplier(morningPeak)).isEqualTo(1.30);

        // 18:30 UTC -> evening peak
        Instant eveningPeak = Instant.parse("2026-10-31T18:30:00Z");
        assertThat(model.trafficMultiplier(eveningPeak)).isEqualTo(1.30);

        // 14:00 UTC -> mid-day off-peak
        Instant midDay = Instant.parse("2026-10-31T14:00:00Z");
        assertThat(model.trafficMultiplier(midDay)).isEqualTo(1.0);

        // 02:00 UTC -> night off-peak
        Instant night = Instant.parse("2026-10-31T02:00:00Z");
        assertThat(model.trafficMultiplier(night)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Travel duration scales inversely with traffic speed reduction")
    void travelDurationUnderCongestion() {
        // Off-peak: 25 km at 25 km/h = exactly 1 hour (3600 seconds)
        Instant offPeak = Instant.parse("2026-10-31T14:00:00Z");
        Duration offPeakDuration = model.travelDuration(25.0, offPeak);
        assertThat(offPeakDuration.toSeconds()).isEqualTo(3600);

        // Peak: 25 km at 25/1.3 = 19.2307 km/h -> 1.3 hours = 4680 seconds
        Instant peak = Instant.parse("2026-10-31T08:30:00Z");
        Duration peakDuration = model.travelDuration(25.0, peak);
        assertThat(peakDuration.toSeconds()).isEqualTo(4680);
    }

    @Test
    @DisplayName("Energy consumption factors in base rate, traffic multiplier, and AC load")
    void energyConsumptionCalculations() {
        // Base: 0.18 kWh/km, AC: 1.10
        // Off-peak: 10 km * 0.18 * 1.0 * 1.10 = 1.98 kWh
        Instant offPeak = Instant.parse("2026-10-31T14:00:00Z");
        double offPeakEnergy = model.calculateEnergy(10.0, offPeak);
        assertThat(offPeakEnergy).isCloseTo(1.98, within(0.001));

        // Peak: 10 km * 0.18 * 1.30 * 1.10 = 2.574 kWh
        Instant peak = Instant.parse("2026-10-31T09:00:00Z");
        double peakEnergy = model.calculateEnergy(10.0, peak);
        assertThat(peakEnergy).isCloseTo(2.574, within(0.001));
    }

    @Test
    @DisplayName("SoC percentage drop for 60 kWh battery")
    void socDropCalculation() {
        Instant offPeak = Instant.parse("2026-10-31T14:00:00Z");
        // 10 km uses 1.98 kWh. On a 60 kWh battery: (1.98 / 60) * 100 = 3.3%
        double socDrop = model.calculateSocDrop(10.0, offPeak, 60.0);
        assertThat(socDrop).isCloseTo(3.30, within(0.01));
    }
}
