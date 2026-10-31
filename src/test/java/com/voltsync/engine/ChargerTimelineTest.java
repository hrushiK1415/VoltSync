package com.voltsync.engine;

import com.voltsync.domain.Charger;
import com.voltsync.domain.ChargerStatus;
import com.voltsync.domain.GeoLocation;
import com.voltsync.domain.Hub;
import com.voltsync.domain.Tariff;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChargerTimelineTest {

    private final Tariff tariff = Tariff.defaultTariff();
    private final GeoLocation locHub = new GeoLocation(12.9716, 77.5946);

    @Test
    @DisplayName("Finds available slot in empty charger timeline")
    void findsSlotInEmptyTimeline() {
        ChargerTimeline timeline = new ChargerTimeline();
        Charger c1 = new Charger("C1", "HUB1", 50.0, ChargerStatus.UP);
        Hub hub = new Hub("HUB1", "T1", locHub, List.of(c1));

        Instant t0800 = Instant.parse("2026-10-31T08:00:00Z");
        Instant t0900 = Instant.parse("2026-10-31T09:00:00Z");

        Optional<ChargerTimeline.AvailableSlot> slot = timeline.findSlot(
                hub, t0800, t0900, Duration.ofMinutes(30), tariff
        );

        assertThat(slot).isPresent();
        assertThat(slot.get().chargerId()).isEqualTo("C1");
        assertThat(slot.get().start()).isEqualTo(t0800);
        assertThat(slot.get().end()).isEqualTo(t0800.plus(Duration.ofMinutes(30)));
    }

    @Test
    @DisplayName("Finds gap between existing reservations")
    void findsGapBetweenReservations() {
        ChargerTimeline timeline = new ChargerTimeline();
        Charger c1 = new Charger("C1", "HUB1", 50.0, ChargerStatus.UP);
        Hub hub = new Hub("HUB1", "T1", locHub, List.of(c1));

        Instant t0800 = Instant.parse("2026-10-31T08:00:00Z");
        Instant t0830 = Instant.parse("2026-10-31T08:30:00Z");
        Instant t0900 = Instant.parse("2026-10-31T09:00:00Z");
        Instant t0930 = Instant.parse("2026-10-31T09:30:00Z");

        // Book 08:00 - 08:30 and 09:00 - 09:30
        timeline.book(new ChargerTimeline.Reservation("HUB1", "C1", "V1", t0800, t0830));
        timeline.book(new ChargerTimeline.Reservation("HUB1", "C1", "V2", t0900, t0930));

        // Looking for 25-minute slot in [08:00, 10:00]
        Optional<ChargerTimeline.AvailableSlot> slot = timeline.findSlot(
                hub, t0800, Instant.parse("2026-10-31T10:00:00Z"), Duration.ofMinutes(25), tariff
        );

        assertThat(slot).isPresent();
        assertThat(slot.get().start()).isEqualTo(t0830);
        assertThat(slot.get().end()).isEqualTo(t0830.plus(Duration.ofMinutes(25)));
    }

    @Test
    @DisplayName("Double booking throws IllegalStateException")
    void doubleBookingThrowsException() {
        ChargerTimeline timeline = new ChargerTimeline();
        Instant t0800 = Instant.parse("2026-10-31T08:00:00Z");
        Instant t0830 = Instant.parse("2026-10-31T08:30:00Z");

        timeline.book(new ChargerTimeline.Reservation("HUB1", "C1", "V1", t0800, t0830));

        // Attempt overlapping reservation
        Instant t0815 = Instant.parse("2026-10-31T08:15:00Z");
        Instant t0845 = Instant.parse("2026-10-31T08:45:00Z");

        assertThatThrownBy(() -> timeline.book(new ChargerTimeline.Reservation("HUB1", "C1", "V2", t0815, t0845)))
                .isInstanceOf(IllegalStateException.class);
    }
}
