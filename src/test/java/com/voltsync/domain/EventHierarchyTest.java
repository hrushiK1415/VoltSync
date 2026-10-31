package com.voltsync.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class EventHierarchyTest {

    private final GeoLocation loc1 = new GeoLocation(12.9716, 77.5946);
    private final GeoLocation loc2 = new GeoLocation(12.9352, 77.6245);
    private final double batteryKWh = 60.0;

    @Test
    @DisplayName("TripEvent drops SoC according to energy used")
    void tripEventSocApplication() {
        Instant start = Instant.parse("2026-10-31T08:00:00Z");
        Instant end = Instant.parse("2026-10-31T08:45:00Z");
        // 6 kWh on a 60 kWh battery -> 10% drop
        TripEvent trip = new TripEvent("T1", start, end, loc1, loc2, 15.0, 6.0, 80.0, 70.0);

        double newSoc = trip.applySoc(80.0, batteryKWh);
        assertThat(newSoc).isCloseTo(70.0, within(0.01));
    }

    @Test
    @DisplayName("DeadheadEvent drops SoC according to repositioning energy used")
    void deadheadEventSocApplication() {
        Instant start = Instant.parse("2026-10-31T07:30:00Z");
        Instant end = Instant.parse("2026-10-31T08:00:00Z");
        // 3 kWh on a 60 kWh battery -> 5% drop
        DeadheadEvent deadhead = new DeadheadEvent(start, end, loc1, loc2, 8.0, 3.0, DeadheadPurpose.TO_TRIP_ORIGIN, 85.0, 80.0);

        double newSoc = deadhead.applySoc(85.0, batteryKWh);
        assertThat(newSoc).isCloseTo(80.0, within(0.01));
    }

    @Test
    @DisplayName("ChargeEvent increases SoC to socTo")
    void chargeEventSocApplication() {
        Instant start = Instant.parse("2026-10-31T06:00:00Z");
        Instant end = Instant.parse("2026-10-31T07:00:00Z");
        ChargeEvent charge = new ChargeEvent("HUB1", "CH1", start, end, loc1, 20.0, 80.0, 4.32, 36.0, 20.0, 80.0);

        double newSoc = charge.applySoc(20.0, batteryKWh);
        assertThat(newSoc).isEqualTo(80.0);
    }

    @Test
    @DisplayName("Polymorphic chain of events accurately tracks vehicle SoC over a shift")
    void polymorphicEventChainSimulation() {
        Instant t0 = Instant.parse("2026-10-31T06:00:00Z");
        Instant t1 = Instant.parse("2026-10-31T06:40:00Z");
        Instant t2 = Instant.parse("2026-10-31T07:00:00Z");
        Instant t3 = Instant.parse("2026-10-31T07:45:00Z");

        // Event sequence: Charge (from 30% to 80%) -> Deadhead (drop 5%) -> Trip (drop 15%)
        List<Event> schedule = List.of(
                new ChargeEvent("H1", "C1", t0, t1, loc1, 30.0, 80.0, 3.6, 30.0, 30.0, 80.0),
                new DeadheadEvent(t1, t2, loc1, loc2, 8.0, 3.0, DeadheadPurpose.TO_TRIP_ORIGIN, 80.0, 75.0),
                new TripEvent("T100", t2, t3, loc2, loc1, 20.0, 9.0, 75.0, 60.0)
        );

        double soc = 30.0;
        for (Event event : schedule) {
            soc = event.applySoc(soc, batteryKWh);
        }

        // Expected: 30 -> 80 -> 75 -> 60
        assertThat(soc).isCloseTo(60.0, within(0.01));
    }
}
