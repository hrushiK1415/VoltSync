package com.voltsync.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class TariffTest {

    private final Tariff tariff = Tariff.defaultTariff(); // Peak: 08-11 & 17-20 @ 0.35, Off-peak @ 0.12

    @Test
    @DisplayName("Peak vs off-peak rate detection")
    void peakDetection() {
        Instant peakTime = Instant.parse("2026-10-31T09:30:00Z");
        Instant offPeakTime = Instant.parse("2026-10-31T14:30:00Z");

        assertThat(tariff.isPeak(peakTime)).isTrue();
        assertThat(tariff.getRateAt(peakTime)).isEqualTo(0.35);

        assertThat(tariff.isPeak(offPeakTime)).isFalse();
        assertThat(tariff.getRateAt(offPeakTime)).isEqualTo(0.12);
    }

    @Test
    @DisplayName("Pure off-peak charging cost calculation")
    void pureOffPeakCost() {
        Instant start = Instant.parse("2026-10-31T01:00:00Z");
        Instant end = Instant.parse("2026-10-31T02:00:00Z");
        double energy = 30.0; // 30 kWh

        // 30 kWh * $0.12 = $3.60
        double cost = tariff.calculateCost(start, end, energy);
        assertThat(cost).isCloseTo(3.60, within(0.01));
    }

    @Test
    @DisplayName("Pure peak charging cost calculation")
    void purePeakCost() {
        Instant start = Instant.parse("2026-10-31T08:30:00Z");
        Instant end = Instant.parse("2026-10-31T09:30:00Z");
        double energy = 30.0;

        // 30 kWh * $0.35 = $10.50
        double cost = tariff.calculateCost(start, end, energy);
        assertThat(cost).isCloseTo(10.50, within(0.01));
    }

    @Test
    @DisplayName("Charging spanning across boundary weights cost by peak fraction")
    void boundarySpanningCost() {
        // From 10:30 to 11:30 (1 hour total):
        // 10:30 - 11:00 is Peak (0.5 hour)
        // 11:00 - 11:30 is Off-peak (0.5 hour)
        Instant start = Instant.parse("2026-10-31T10:30:00Z");
        Instant end = Instant.parse("2026-10-31T11:30:00Z");
        double energy = 20.0;

        // Effective rate = (0.35 + 0.12) / 2 = 0.235
        // Cost = 20 * 0.235 = $4.70
        double cost = tariff.calculateCost(start, end, energy);
        assertThat(cost).isCloseTo(4.70, within(0.05));
    }
}
