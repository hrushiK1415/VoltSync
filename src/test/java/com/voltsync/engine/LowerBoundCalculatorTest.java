package com.voltsync.engine;

import com.voltsync.domain.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class LowerBoundCalculatorTest {

    private final LowerBoundCalculator calculator = new LowerBoundCalculator();
    private final ConsumptionModel model = ConsumptionModel.defaultModel();
    private final Tariff tariff = Tariff.defaultTariff();
    private final GeoLocation loc1 = new GeoLocation(12.9716, 77.5946);
    private final GeoLocation loc2 = new GeoLocation(12.9352, 77.6245);

    @Test
    @DisplayName("Identifies individually infeasible trips for unassigned lower bound")
    void detectsIndividuallyInfeasibleTrips() {
        Vehicle v1 = new Vehicle("V1", "T1", 60.0, 90.0, 0.18, loc1, "H1");
        Hub h1 = new Hub("H1", "T1", loc1, List.of(new Charger("C1", "H1", 50.0, ChargerStatus.UP)));

        Instant t0800 = Instant.parse("2026-10-31T08:00:00Z");

        // Feasible trip: 10 km with 60 min window (peak duration is ~31 mins)
        Trip feasible = new Trip("T_OK", "T1", t0800, loc1, loc2, 10.0, t0800.plusSeconds(3600));

        // Infeasible trip: 50 km with only 10 min window (speed 25 km/h requires 2 hours!)
        Trip infeasible = new Trip("T_FAIL", "T1", t0800, loc1, loc2, 50.0, t0800.plusSeconds(600));

        LowerBoundCalculator.LowerBoundResult result = calculator.calculate(
                List.of(v1), List.of(h1), List.of(feasible, infeasible), tariff, model
        );

        assertThat(result.unassignedLB()).isEqualTo(1);
        assertThat(result.costLB()).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("Computes cost lower bound from base energy and off-peak tariff rate")
    void computesCostLowerBound() {
        Vehicle v1 = new Vehicle("V1", "T1", 60.0, 90.0, 0.18, loc1, "H1");
        Hub h1 = new Hub("H1", "T1", loc1, List.of(new Charger("C1", "H1", 50.0, ChargerStatus.UP)));

        // Midday trip (off-peak, 10 km)
        Instant t1200 = Instant.parse("2026-10-31T12:00:00Z");
        Trip trip = new Trip("T1", "T1", t1200, loc1, loc2, 10.0, t1200.plusSeconds(3600));

        LowerBoundCalculator.LowerBoundResult result = calculator.calculate(
                List.of(v1), List.of(h1), List.of(trip), tariff, model
        );

        // 10 km * 0.18 * 1.0 * 1.1 = 1.98 kWh
        // Cost LB = 1.98 * 0.12 = 0.2376
        assertThat(result.costLB()).isCloseTo(0.2376, within(0.001));
    }
}
