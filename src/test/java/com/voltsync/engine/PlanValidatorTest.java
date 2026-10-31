package com.voltsync.engine;

import com.voltsync.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class PlanValidatorTest {

    private PlanValidator validator;

    private GeoLocation locDepot;
    private GeoLocation locA;
    private GeoLocation locB;
    private GeoLocation locHub;

    private Vehicle cab1;
    private Vehicle cab2;
    private Vehicle cab3;

    private Hub hub1;
    private Charger charger1;
    private Charger charger2;

    private Trip trip1;
    private Trip trip2;
    private Trip trip3;

    @BeforeEach
    void setUp() {
        validator = new PlanValidator();

        locDepot = new GeoLocation(12.9716, 77.5946); // Central depot
        locA = new GeoLocation(12.9352, 77.6245);     // Origin A (~5 km)
        locB = new GeoLocation(12.9141, 77.6109);     // Destination B (~3 km from A)
        locHub = new GeoLocation(12.9500, 77.6000);   // Hub (~3 km)

        cab1 = new Vehicle("CAB1", "T1", 60.0, 80.0, 0.18, locDepot, "HUB1");
        cab2 = new Vehicle("CAB2", "T1", 60.0, 80.0, 0.18, locDepot, "HUB1");
        cab3 = new Vehicle("CAB3", "T1", 60.0, 80.0, 0.18, locDepot, "HUB1");

        charger1 = new Charger("CH1", "HUB1", 50.0, ChargerStatus.UP);
        charger2 = new Charger("CH2", "HUB1", 50.0, ChargerStatus.UP);
        hub1 = new Hub("HUB1", "T1", locHub, List.of(charger1, charger2));

        Instant t0800 = Instant.parse("2026-10-31T08:00:00Z");
        Instant t0830 = Instant.parse("2026-10-31T08:30:00Z");
        Instant t0900 = Instant.parse("2026-10-31T09:00:00Z");
        Instant t0930 = Instant.parse("2026-10-31T09:30:00Z");

        trip1 = new Trip("TRIP1", "T1", t0800, locDepot, locA, 5.0, t0830);
        trip2 = new Trip("TRIP2", "T1", t0830, locA, locB, 3.0, t0900);
        trip3 = new Trip("TRIP3", "T1", t0900, locB, locHub, 4.0, t0930);
    }

    @Test
    @DisplayName("Valid plan satisfies all constraints and passes validation")
    void validPlanPassesValidation() {
        Instant t0800 = Instant.parse("2026-10-31T08:00:00Z");
        Instant t0820 = Instant.parse("2026-10-31T08:20:00Z");
        Instant t0830 = Instant.parse("2026-10-31T08:30:00Z");
        Instant t0850 = Instant.parse("2026-10-31T08:50:00Z");

        TripEvent e1 = new TripEvent("TRIP1", t0800, t0820, locDepot, locA, 5.0, 1.0, 80.0, 78.33);
        TripEvent e2 = new TripEvent("TRIP2", t0830, t0850, locA, locB, 3.0, 0.6, 78.33, 77.33);

        Map<String, List<Event>> schedules = new HashMap<>();
        schedules.put("CAB1", List.of(e1, e2));
        schedules.put("CAB2", Collections.emptyList());
        schedules.put("CAB3", Collections.emptyList());

        List<UnassignedTrip> unassigned = List.of(
                new UnassignedTrip("TRIP3", UnassignedReason.NO_CAB_IN_TIME, "No cab in proximity")
        );

        Plan plan = new Plan("PLAN_VALID", 1, schedules, unassigned, 10.0, 8.0, Map.of());

        ValidationReport report = validator.validate(plan, List.of(cab1, cab2, cab3), List.of(hub1), List.of(trip1, trip2, trip3));
        assertThat(report.valid()).isTrue();
        assertThat(report.violations()).isEmpty();
    }

    @Test
    @DisplayName("Hard Rule 1: Battery floor breach (<15% SoC) fails validation")
    void batteryFloorBreachFailsValidation() {
        // Cab starts at 20% SoC
        cab1.setSocPct(20.0);

        Instant t0800 = Instant.parse("2026-10-31T08:00:00Z");
        Instant t0820 = Instant.parse("2026-10-31T08:20:00Z");

        // 6 kWh energy used on 60 kWh battery -> 10% drop -> SoC becomes 10% (< 15% floor!)
        TripEvent e1 = new TripEvent("TRIP1", t0800, t0820, locDepot, locA, 5.0, 6.0, 20.0, 10.0);

        Plan plan = new Plan("PLAN_FLOOR_FAIL", 1,
                Map.of("CAB1", List.of(e1)),
                List.of(new UnassignedTrip("TRIP2", UnassignedReason.NO_CAB_IN_TIME),
                        new UnassignedTrip("TRIP3", UnassignedReason.NO_CAB_IN_TIME)),
                0.0, 0.0, Map.of());

        ValidationReport report = validator.validate(plan, List.of(cab1), List.of(hub1), List.of(trip1, trip2, trip3));
        assertThat(report.valid()).isFalse();
        assertThat(report.violations())
                .anyMatch(v -> v.code().equals("FLOOR_BREACH") && v.entityId().equals("CAB1"));
    }

    @Test
    @DisplayName("Hard Rule 2: Concurrent charging exceeding active chargers at a hub fails validation")
    void chargerOverbookingFailsValidation() {
        // Hub1 has only 2 chargers (CH1, CH2).
        // If 3 cabs charge concurrently at HUB1:
        Instant t0800 = Instant.parse("2026-10-31T08:00:00Z");
        Instant t0810 = Instant.parse("2026-10-31T08:10:00Z");
        Instant t0840 = Instant.parse("2026-10-31T08:40:00Z");
        Instant t0845 = Instant.parse("2026-10-31T08:45:00Z");

        // Cab locations set to hub
        cab1.setLocation(locHub);
        cab2.setLocation(locHub);
        cab3.setLocation(locHub);

        ChargeEvent c1 = new ChargeEvent("HUB1", "CH1", t0800, t0845, locHub, 20.0, 80.0, 5.0, 36.0, 20.0, 80.0);
        ChargeEvent c2 = new ChargeEvent("HUB1", "CH2", t0800, t0845, locHub, 20.0, 80.0, 5.0, 36.0, 20.0, 80.0);
        ChargeEvent c3 = new ChargeEvent("HUB1", "CH1", t0810, t0840, locHub, 20.0, 80.0, 5.0, 36.0, 20.0, 80.0); // 3rd concurrent!

        Map<String, List<Event>> schedules = Map.of(
                "CAB1", List.of(c1),
                "CAB2", List.of(c2),
                "CAB3", List.of(c3)
        );

        Plan plan = new Plan("PLAN_OVERBOOKED", 1, schedules,
                List.of(new UnassignedTrip("TRIP1", UnassignedReason.NO_CAB_IN_TIME),
                        new UnassignedTrip("TRIP2", UnassignedReason.NO_CAB_IN_TIME),
                        new UnassignedTrip("TRIP3", UnassignedReason.NO_CAB_IN_TIME)),
                15.0, 0.0, Map.of());

        ValidationReport report = validator.validate(plan, List.of(cab1, cab2, cab3), List.of(hub1), List.of(trip1, trip2, trip3));
        assertThat(report.valid()).isFalse();
        assertThat(report.violations())
                .anyMatch(v -> v.code().equals("CHARGER_OVERBOOKING") && v.entityId().equals("HUB1"));
    }

    @Test
    @DisplayName("Using a broken (DOWN) charger fails validation")
    void brokenChargerFailsValidation() {
        charger2.setStatus(ChargerStatus.DOWN);
        cab1.setLocation(locHub);

        Instant t0800 = Instant.parse("2026-10-31T08:00:00Z");
        Instant t0830 = Instant.parse("2026-10-31T08:30:00Z");

        ChargeEvent c1 = new ChargeEvent("HUB1", "CH2", t0800, t0830, locHub, 20.0, 60.0, 4.0, 24.0, 20.0, 60.0);

        Plan plan = new Plan("PLAN_BROKEN_CH", 1,
                Map.of("CAB1", List.of(c1)),
                List.of(new UnassignedTrip("TRIP1", UnassignedReason.NO_CAB_IN_TIME),
                        new UnassignedTrip("TRIP2", UnassignedReason.NO_CAB_IN_TIME),
                        new UnassignedTrip("TRIP3", UnassignedReason.NO_CAB_IN_TIME)),
                4.0, 0.0, Map.of());

        ValidationReport report = validator.validate(plan, List.of(cab1), List.of(hub1), List.of(trip1, trip2, trip3));
        assertThat(report.valid()).isFalse();
        assertThat(report.violations())
                .anyMatch(v -> v.code().equals("BROKEN_CHARGER_USED") && v.entityId().equals("CH2"));
    }

    @Test
    @DisplayName("Hard Rule 3: Trip completing after latestFinish fails validation")
    void lateTripFailsValidation() {
        Instant t0800 = Instant.parse("2026-10-31T08:00:00Z");
        // Trip 1 latestFinish is 08:30, but event ends at 08:45
        Instant t0845 = Instant.parse("2026-10-31T08:45:00Z");

        TripEvent lateEvent = new TripEvent("TRIP1", t0800, t0845, locDepot, locA, 5.0, 1.0, 80.0, 78.33);

        Plan plan = new Plan("PLAN_LATE", 1,
                Map.of("CAB1", List.of(lateEvent)),
                List.of(new UnassignedTrip("TRIP2", UnassignedReason.NO_CAB_IN_TIME),
                        new UnassignedTrip("TRIP3", UnassignedReason.NO_CAB_IN_TIME)),
                0.0, 0.0, Map.of());

        ValidationReport report = validator.validate(plan, List.of(cab1), List.of(hub1), List.of(trip1, trip2, trip3));
        assertThat(report.valid()).isFalse();
        assertThat(report.violations())
                .anyMatch(v -> v.code().equals("LATE_TRIP") && v.entityId().equals("TRIP1"));
    }

    @Test
    @DisplayName("Trip not starting exactly on time fails validation")
    void tripNotOnTimeFailsValidation() {
        // Trip 1 startTime is 08:00:00Z, but scheduled at 08:05:00Z
        Instant t0805 = Instant.parse("2026-10-31T08:05:00Z");
        Instant t0825 = Instant.parse("2026-10-31T08:25:00Z");

        TripEvent delayedTrip = new TripEvent("TRIP1", t0805, t0825, locDepot, locA, 5.0, 1.0, 80.0, 78.33);

        Plan plan = new Plan("PLAN_NOT_ON_TIME", 1,
                Map.of("CAB1", List.of(delayedTrip)),
                List.of(new UnassignedTrip("TRIP2", UnassignedReason.NO_CAB_IN_TIME),
                        new UnassignedTrip("TRIP3", UnassignedReason.NO_CAB_IN_TIME)),
                0.0, 0.0, Map.of());

        ValidationReport report = validator.validate(plan, List.of(cab1), List.of(hub1), List.of(trip1, trip2, trip3));
        assertThat(report.valid()).isFalse();
        assertThat(report.violations())
                .anyMatch(v -> v.code().equals("TRIP_NOT_ON_TIME") && v.entityId().equals("TRIP1"));
    }

    @Test
    @DisplayName("Overlapping events on the same cab fail validation")
    void overlappingEventsFailValidation() {
        Instant t0800 = Instant.parse("2026-10-31T08:00:00Z");
        Instant t0825 = Instant.parse("2026-10-31T08:25:00Z");
        Instant t0820 = Instant.parse("2026-10-31T08:20:00Z"); // Overlaps with previous ending at 08:25
        Instant t0840 = Instant.parse("2026-10-31T08:40:00Z");

        TripEvent e1 = new TripEvent("TRIP1", t0800, t0825, locDepot, locA, 5.0, 1.0, 80.0, 78.33);
        DeadheadEvent e2 = new DeadheadEvent(t0820, t0840, locA, locB, 3.0, 0.5, DeadheadPurpose.TO_TRIP_ORIGIN, 78.33, 77.5);

        Plan plan = new Plan("PLAN_OVERLAP", 1,
                Map.of("CAB1", List.of(e1, e2)),
                List.of(new UnassignedTrip("TRIP2", UnassignedReason.NO_CAB_IN_TIME),
                        new UnassignedTrip("TRIP3", UnassignedReason.NO_CAB_IN_TIME)),
                0.0, 0.0, Map.of());

        ValidationReport report = validator.validate(plan, List.of(cab1), List.of(hub1), List.of(trip1, trip2, trip3));
        assertThat(report.valid()).isFalse();
        assertThat(report.violations())
                .anyMatch(v -> v.code().equals("OVERLAPPING_EVENTS") && v.entityId().equals("CAB1"));
    }

    @Test
    @DisplayName("Spatial location discontinuity (teleportation without deadhead) fails validation")
    void locationDiscontinuityFailsValidation() {
        Instant t0800 = Instant.parse("2026-10-31T08:00:00Z");
        Instant t0820 = Instant.parse("2026-10-31T08:20:00Z");
        Instant t0830 = Instant.parse("2026-10-31T08:30:00Z");
        Instant t0850 = Instant.parse("2026-10-31T08:50:00Z");

        // e1 ends at locA, but e2 starts at locHub without a DeadheadEvent connecting locA -> locHub!
        TripEvent e1 = new TripEvent("TRIP1", t0800, t0820, locDepot, locA, 5.0, 1.0, 80.0, 78.33);
        TripEvent e2 = new TripEvent("TRIP2", t0830, t0850, locHub, locB, 3.0, 0.6, 78.33, 77.33);

        Plan plan = new Plan("PLAN_TELEPORT", 1,
                Map.of("CAB1", List.of(e1, e2)),
                List.of(new UnassignedTrip("TRIP3", UnassignedReason.NO_CAB_IN_TIME)),
                0.0, 0.0, Map.of());

        ValidationReport report = validator.validate(plan, List.of(cab1), List.of(hub1), List.of(trip1, trip2, trip3));
        assertThat(report.valid()).isFalse();
        assertThat(report.violations())
                .anyMatch(v -> v.code().equals("LOCATION_DISCONTINUITY") && v.entityId().equals("CAB1"));
    }

    @Test
    @DisplayName("Trip assignment anomalies (duplicate assignment, unaccounted trip) fail validation")
    void tripAssignmentAnomaliesFailValidation() {
        Instant t0800 = Instant.parse("2026-10-31T08:00:00Z");
        Instant t0820 = Instant.parse("2026-10-31T08:20:00Z");

        // Trip1 assigned to CAB1 AND CAB2
        TripEvent e1 = new TripEvent("TRIP1", t0800, t0820, locDepot, locA, 5.0, 1.0, 80.0, 78.33);
        TripEvent e2 = new TripEvent("TRIP1", t0800, t0820, locDepot, locA, 5.0, 1.0, 80.0, 78.33);

        // TRIP2 is missing completely (neither assigned nor unassigned)
        // TRIP3 is marked unassigned
        Plan plan = new Plan("PLAN_ANOMALY", 1,
                Map.of("CAB1", List.of(e1), "CAB2", List.of(e2)),
                List.of(new UnassignedTrip("TRIP3", UnassignedReason.NO_CAB_IN_TIME)),
                0.0, 0.0, Map.of());

        ValidationReport report = validator.validate(plan, List.of(cab1, cab2), List.of(hub1), List.of(trip1, trip2, trip3));
        assertThat(report.valid()).isFalse();
        assertThat(report.violations())
                .anyMatch(v -> v.code().equals("DUPLICATE_TRIP_ASSIGNMENT") && v.entityId().equals("TRIP1"))
                .anyMatch(v -> v.code().equals("UNACCOUNTED_TRIP") && v.entityId().equals("TRIP2"));
    }
}
