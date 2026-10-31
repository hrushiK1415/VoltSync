package com.voltsync.engine;

import com.voltsync.domain.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GreedyLocalSearchSolverTest {

    private final GreedyLocalSearchSolver solver = new GreedyLocalSearchSolver();
    private final PlanValidator validator = new PlanValidator();
    private final ConsumptionModel model = ConsumptionModel.defaultModel();
    private final Tariff tariff = Tariff.defaultTariff();

    private final GeoLocation depot = new GeoLocation(12.9716, 77.5946);
    private final GeoLocation locA = new GeoLocation(12.9352, 77.6245);
    private final GeoLocation locB = new GeoLocation(12.9141, 77.6109);
    private final GeoLocation hubLoc = new GeoLocation(12.9500, 77.6000);

    @Test
    @DisplayName("Solver creates a valid plan that passes independent PlanValidator")
    void solverGeneratesValidPlan() {
        Vehicle cab1 = new Vehicle("CAB1", "T1", 60.0, 90.0, 0.18, depot, "HUB1");
        Vehicle cab2 = new Vehicle("CAB2", "T1", 60.0, 85.0, 0.18, depot, "HUB1");

        Charger ch1 = new Charger("CH1", "HUB1", 50.0, ChargerStatus.UP);
        Charger ch2 = new Charger("CH2", "HUB1", 50.0, ChargerStatus.UP);
        Hub hub = new Hub("HUB1", "T1", hubLoc, List.of(ch1, ch2));

        Instant t0800 = Instant.parse("2026-10-31T08:00:00Z");
        Instant t0900 = Instant.parse("2026-10-31T09:00:00Z");
        Instant t1000 = Instant.parse("2026-10-31T10:00:00Z");

        Trip trip1 = new Trip("TRIP1", "T1", t0800, depot, locA, 5.0, t0800.plusSeconds(2400));
        Trip trip2 = new Trip("TRIP2", "T1", t0900, locA, locB, 4.0, t0900.plusSeconds(2400));
        Trip trip3 = new Trip("TRIP3", "T1", t1000, locB, depot, 6.0, t1000.plusSeconds(2400));

        Solver.SolverInput input = new Solver.SolverInput(
                "PLAN_TEST_1", List.of(cab1, cab2), List.of(hub), List.of(trip1, trip2, trip3),
                tariff, model, Duration.ofSeconds(5)
        );

        Plan plan = solver.solve(input);

        assertThat(plan).isNotNull();
        assertThat(plan.getPlanId()).isEqualTo("PLAN_TEST_1");

        // Validate plan with independent validator
        ValidationReport report = validator.validate(plan, List.of(cab1, cab2), List.of(hub), List.of(trip1, trip2, trip3));
        assertThat(report.valid()).isTrue();
        assertThat(report.violations()).isEmpty();
    }

    @Test
    @DisplayName("Solver marks individually impossible trips with TIME_WINDOW_INFEASIBLE")
    void solverMarksInfeasibleTrips() {
        Vehicle cab1 = new Vehicle("CAB1", "T1", 60.0, 90.0, 0.18, depot, "HUB1");
        Hub hub = new Hub("HUB1", "T1", hubLoc, List.of(new Charger("CH1", "HUB1", 50.0, ChargerStatus.UP)));

        Instant t0800 = Instant.parse("2026-10-31T08:00:00Z");
        // 50 km distance in 5 minutes is physically impossible
        Trip impossible = new Trip("TRIP_IMP", "T1", t0800, depot, locA, 50.0, t0800.plusSeconds(300));

        Solver.SolverInput input = new Solver.SolverInput(
                "PLAN_IMP", List.of(cab1), List.of(hub), List.of(impossible),
                tariff, model, Duration.ofSeconds(2)
        );

        Plan plan = solver.solve(input);

        assertThat(plan.getUnassigned()).hasSize(1);
        assertThat(plan.getUnassigned().get(0).reason()).isEqualTo(UnassignedReason.TIME_WINDOW_INFEASIBLE);
    }
}
