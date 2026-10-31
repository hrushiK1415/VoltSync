package com.voltsync.engine;

import com.voltsync.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IncrementalReplannerTest {

    private IncrementalReplanner replanner;
    private ConsumptionModel model;
    private Tariff tariff;

    private GeoLocation depot;
    private GeoLocation locA;
    private GeoLocation locB;
    private GeoLocation locHub;

    private Vehicle cab1;
    private Vehicle cab2;
    private Hub hub1;
    private Charger charger1;
    private Charger charger2;

    private Trip trip1;
    private Trip trip2;

    @BeforeEach
    void setUp() {
        replanner = new IncrementalReplanner();
        model = ConsumptionModel.defaultModel();
        tariff = Tariff.defaultTariff();

        depot = new GeoLocation(12.9716, 77.5946);
        locA = new GeoLocation(12.9352, 77.6245);
        locB = new GeoLocation(12.9141, 77.6109);
        locHub = new GeoLocation(12.9500, 77.6000);

        cab1 = new Vehicle("CAB1", "T1", 60.0, 80.0, 0.18, depot, "HUB1");
        cab2 = new Vehicle("CAB2", "T1", 60.0, 80.0, 0.18, depot, "HUB1");

        charger1 = new Charger("CH1", "HUB1", 50.0, ChargerStatus.UP);
        charger2 = new Charger("CH2", "HUB1", 50.0, ChargerStatus.UP);
        hub1 = new Hub("HUB1", "T1", locHub, List.of(charger1, charger2));

        Instant t0800 = Instant.parse("2026-10-31T08:00:00Z");
        Instant t0830 = Instant.parse("2026-10-31T08:30:00Z");
        Instant t0900 = Instant.parse("2026-10-31T09:00:00Z");
        Instant t0930 = Instant.parse("2026-10-31T09:30:00Z");

        trip1 = new Trip("TRIP1", "T1", t0800, depot, locA, 5.0, t0830);
        trip2 = new Trip("TRIP2", "T1", t0900, locA, locB, 4.0, t0930);
    }

    @Test
    @DisplayName("Minor battery drift without constraint violation completes with 0 changed assignments in < 2s")
    void minorBatteryDriftNoViolation() {
        Instant t0800 = Instant.parse("2026-10-31T08:00:00Z");
        Instant t0820 = Instant.parse("2026-10-31T08:20:00Z");
        Instant t0900 = Instant.parse("2026-10-31T09:00:00Z");
        Instant t0920 = Instant.parse("2026-10-31T09:20:00Z");

        TripEvent e1 = new TripEvent("TRIP1", t0800, t0820, depot, locA, 5.0, 1.0, 80.0, 78.33);
        TripEvent e2 = new TripEvent("TRIP2", t0900, t0920, locA, locB, 4.0, 0.8, 78.33, 77.0);

        Map<String, List<Event>> schedules = new HashMap<>();
        schedules.put("CAB1", List.of(e1, e2));
        schedules.put("CAB2", List.of());

        Plan plan = new Plan("PLAN_1", 1, schedules, List.of(), 0.0, 0.0, Map.of());
        ChargerTimeline timeline = new ChargerTimeline();

        // At 08:30, CAB1 reports SoC is 72% instead of 78.33% (drift, but safely above 15% floor for next trip)
        Instant atTime = Instant.parse("2026-10-31T08:30:00Z");

        long startNs = System.nanoTime();
        ReplanResult result = replanner.handleBatteryDrift(
                plan, "CAB1", 72.0, atTime,
                List.of(cab1, cab2), List.of(hub1), List.of(trip1, trip2),
                tariff, model, timeline
        );
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;

        assertThat(elapsedMs).isLessThan(2000);
        assertThat(result.validationReport().valid()).isTrue();
        assertThat(result.diff().changedAssignmentsCount()).isZero();
    }

    @Test
    @DisplayName("Severe battery drift recovers by inserting charging session before trip in < 2s")
    void severeBatteryDriftInsertsChargeSession() {
        Instant t0800 = Instant.parse("2026-10-31T08:00:00Z");
        Instant t0820 = Instant.parse("2026-10-31T08:20:00Z");
        Instant t0900 = Instant.parse("2026-10-31T09:00:00Z");
        Instant t0920 = Instant.parse("2026-10-31T09:20:00Z");

        TripEvent e1 = new TripEvent("TRIP1", t0800, t0820, depot, locA, 5.0, 1.0, 80.0, 78.33);
        TripEvent e2 = new TripEvent("TRIP2", t0900, t0920, locA, locB, 4.0, 1.2, 78.33, 76.33);

        Map<String, List<Event>> schedules = new HashMap<>();
        schedules.put("CAB1", List.of(e1, e2));
        schedules.put("CAB2", List.of());

        Plan plan = new Plan("PLAN_DRIFT_CHARGE", 1, schedules, List.of(), 0.0, 0.0, Map.of());
        ChargerTimeline timeline = new ChargerTimeline();

        // At 08:30, CAB1 reports critical battery: 15.5% (trip 2 would breach 15.0% floor!)
        Instant atTime = Instant.parse("2026-10-31T08:30:00Z");

        long startNs = System.nanoTime();
        ReplanResult result = replanner.handleBatteryDrift(
                plan, "CAB1", 15.5, atTime,
                List.of(cab1, cab2), List.of(hub1), List.of(trip1, trip2),
                tariff, model, timeline
        );
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;

        assertThat(elapsedMs).isLessThan(2000);
        assertThat(result.validationReport().valid()).isTrue();
        // A charging slot was added to recover CAB1's battery before TRIP2
        assertThat(result.diff().newlyScheduledCharges()).hasSize(1);
        assertThat(result.plan().getVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("Severe battery drift with no charger available reassigns trip to another cab in < 2s")
    void severeBatteryDriftReassignsTripWhenNoCharger() {
        Instant t0800 = Instant.parse("2026-10-31T08:00:00Z");
        Instant t0820 = Instant.parse("2026-10-31T08:20:00Z");
        Instant t0900 = Instant.parse("2026-10-31T09:00:00Z");
        Instant t0920 = Instant.parse("2026-10-31T09:20:00Z");

        TripEvent e1 = new TripEvent("TRIP1", t0800, t0820, depot, locA, 5.0, 1.0, 80.0, 78.33);
        TripEvent e2 = new TripEvent("TRIP2", t0900, t0920, locA, locB, 4.0, 1.2, 78.33, 76.33);

        Map<String, List<Event>> schedules = new HashMap<>();
        schedules.put("CAB1", List.of(e1, e2));
        schedules.put("CAB2", List.of()); // CAB2 is ready at depot

        Plan plan = new Plan("PLAN_DRIFT_REASSIGN", 1, schedules, List.of(), 0.0, 0.0, Map.of());
        ChargerTimeline timeline = new ChargerTimeline();

        // Hub chargers are unavailable / booked
        charger1.setStatus(ChargerStatus.DOWN);
        charger2.setStatus(ChargerStatus.DOWN);

        Instant atTime = Instant.parse("2026-10-31T08:30:00Z");

        long startNs = System.nanoTime();
        ReplanResult result = replanner.handleBatteryDrift(
                plan, "CAB1", 15.5, atTime,
                List.of(cab1, cab2), List.of(hub1), List.of(trip1, trip2),
                tariff, model, timeline
        );
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;

        assertThat(elapsedMs).isLessThan(2000);
        assertThat(result.validationReport().valid()).isTrue();
        // Since no charger was available, trip was reassigned to CAB2!
        assertThat(result.diff().changedAssignmentsCount()).isEqualTo(1);
        assertThat(result.diff().reassignedTrips()).containsEntry("TRIP2", "CAB2");
    }

    @Test
    @DisplayName("Charger failure moves session to alternative UP charger at same hub in < 2s")
    void chargerFailureReroutesToSameHubStall() {
        Instant t0800 = Instant.parse("2026-10-31T08:00:00Z");
        Instant t0845 = Instant.parse("2026-10-31T08:45:00Z");

        ChargeEvent ce = new ChargeEvent("HUB1", "CH1", t0800, t0845, locHub, 20.0, 80.0, 4.5, 36.0, 20.0, 80.0);
        cab1.setLocation(locHub);

        Map<String, List<Event>> schedules = new HashMap<>();
        schedules.put("CAB1", List.of(ce));

        Plan plan = new Plan("PLAN_CH_FAIL", 1, schedules,
                List.of(new UnassignedTrip("TRIP1", UnassignedReason.NO_CAB_IN_TIME),
                        new UnassignedTrip("TRIP2", UnassignedReason.NO_CAB_IN_TIME)),
                4.5, 0.0, Map.of());

        ChargerTimeline timeline = new ChargerTimeline();
        timeline.book(new ChargerTimeline.Reservation("HUB1", "CH1", "CAB1", t0800, t0845));

        // CH1 breaks at 07:55
        Instant atTime = Instant.parse("2026-10-31T07:55:00Z");

        long startNs = System.nanoTime();
        ReplanResult result = replanner.handleChargerFailure(
                plan, "CH1", atTime,
                List.of(cab1), List.of(hub1), List.of(trip1, trip2),
                tariff, model, timeline
        );
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;

        assertThat(elapsedMs).isLessThan(2000);
        assertThat(result.validationReport().valid()).isTrue();

        // CH1 is marked DOWN, session moved to CH2
        assertThat(charger1.getStatus()).isEqualTo(ChargerStatus.DOWN);
        assertThat(result.diff().newlyScheduledCharges()).hasSize(1);
        assertThat(result.diff().newlyScheduledCharges().get(0).getChargerId()).isEqualTo("CH2");
    }

    @Test
    @DisplayName("Scale re-plan benchmark: battery drift on 300 cab fleet re-plans in < 2s")
    void scaleBenchmarkBatteryDriftReplan() {
        SyntheticDataGenerator generator = new SyntheticDataGenerator();
        SyntheticDataGenerator.Scenario scenario = generator.generateBenchmarkScenario(42L, 300, 40, 1500);

        GreedyLocalSearchSolver solver = new GreedyLocalSearchSolver();
        Solver.SolverInput input = new Solver.SolverInput(
                "PLAN_BENCH_REPLAN",
                scenario.vehicles(), scenario.hubs(), scenario.trips(),
                scenario.tariff(), scenario.model(), Duration.ofSeconds(15)
        );
        Plan initialPlan = solver.solve(input);

        // Find a cab that has scheduled trips
        String testCabId = initialPlan.getCabSchedules().entrySet().stream()
                .filter(e -> !e.getValue().isEmpty())
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse("CAB-001");

        ChargerTimeline timeline = new ChargerTimeline();
        Instant atTime = Instant.parse("2026-10-31T10:00:00Z");

        long startNs = System.nanoTime();
        ReplanResult result = replanner.handleBatteryDrift(
                initialPlan, testCabId, 22.0, atTime,
                scenario.vehicles(), scenario.hubs(), scenario.trips(),
                scenario.tariff(), scenario.model(), timeline
        );
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;

        System.out.println("Scale Battery Drift Re-plan Duration: " + elapsedMs + " ms");
        assertThat(elapsedMs).isLessThan(2000);
        assertThat(result.validationReport().valid()).isTrue();
    }
}
