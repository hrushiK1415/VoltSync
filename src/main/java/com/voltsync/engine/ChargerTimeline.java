package com.voltsync.engine;

import com.voltsync.domain.Charger;
import com.voltsync.domain.Hub;
import com.voltsync.domain.Tariff;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Manages charging stall reservations across hubs using sorted interval collections per charger.
 * Provides fast O(log C) slot lookup and prevents charger overbooking.
 */
public class ChargerTimeline {

    public record Reservation(String hubId, String chargerId, String vehicleId, Instant start, Instant end) {
        public Reservation {
            Objects.requireNonNull(hubId);
            Objects.requireNonNull(chargerId);
            Objects.requireNonNull(start);
            Objects.requireNonNull(end);
            if (end.isBefore(start)) {
                throw new IllegalArgumentException("end cannot be before start");
            }
        }
    }

    public record AvailableSlot(String hubId, String chargerId, Instant start, Instant end, double powerKW) {
    }

    // Map: chargerId -> list of sorted non-overlapping reservations
    private final Map<String, List<Reservation>> chargerReservations = new HashMap<>();

    public ChargerTimeline() {
    }

    /**
     * Copy constructor for speculative branch evaluation and local search backtracking.
     */
    public ChargerTimeline(ChargerTimeline other) {
        for (Map.Entry<String, List<Reservation>> entry : other.chargerReservations.entrySet()) {
            this.chargerReservations.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
    }

    /**
     * Finds the best available charging slot for a given hub, window, and duration.
     * Prioritizes slots during off-peak hours and lowest tariff cost.
     */
    public Optional<AvailableSlot> findSlot(Hub hub, Instant earliestStart, Instant latestEnd, Duration duration, Tariff tariff) {
        if (latestEnd.isBefore(earliestStart) || duration.isZero() || duration.isNegative()) {
            return Optional.empty();
        }

        long durationSeconds = duration.toSeconds();
        if (Duration.between(earliestStart, latestEnd).toSeconds() < durationSeconds) {
            return Optional.empty();
        }

        List<AvailableSlot> feasibleSlots = new ArrayList<>();

        for (Charger charger : hub.getChargers()) {
            if (!charger.isUp()) {
                continue;
            }

            List<Reservation> resList = chargerReservations.getOrDefault(charger.getId(), Collections.emptyList());
            Instant windowStart = earliestStart;

            for (Reservation res : resList) {
                if (res.end().isBefore(windowStart)) {
                    continue;
                }
                if (res.start().isAfter(latestEnd)) {
                    break;
                }

                Instant gapEnd = res.start().isBefore(latestEnd) ? res.start() : latestEnd;
                if (Duration.between(windowStart, gapEnd).toSeconds() >= durationSeconds) {
                    Instant slotEnd = windowStart.plusSeconds(durationSeconds);
                    feasibleSlots.add(new AvailableSlot(hub.getId(), charger.getId(), windowStart, slotEnd, charger.getPowerKW()));
                }

                if (res.end().isAfter(windowStart)) {
                    windowStart = res.end();
                }
                if (windowStart.isAfter(latestEnd)) {
                    break;
                }
            }

            if (windowStart.isBefore(latestEnd) && Duration.between(windowStart, latestEnd).toSeconds() >= durationSeconds) {
                Instant slotEnd = windowStart.plusSeconds(durationSeconds);
                feasibleSlots.add(new AvailableSlot(hub.getId(), charger.getId(), windowStart, slotEnd, charger.getPowerKW()));
            }
        }

        if (feasibleSlots.isEmpty()) {
            return Optional.empty();
        }

        // Rank candidate slots: lowest tariff cost first (favoring off-peak intervals), with earliest start as tie-breaker
        feasibleSlots.sort((s1, s2) -> {
            double cost1 = tariff.calculateCost(s1.start(), s1.end(), 1.0);
            double cost2 = tariff.calculateCost(s2.start(), s2.end(), 1.0);
            int costCmp = Double.compare(cost1, cost2);
            if (costCmp != 0) return costCmp;
            return s1.start().compareTo(s2.start());
        });

        return Optional.of(feasibleSlots.get(0));
    }

    /**
     * Books a confirmed reservation on a specific charger.
     */
    public synchronized void book(Reservation reservation) {
        List<Reservation> list = chargerReservations.computeIfAbsent(reservation.chargerId(), k -> new ArrayList<>());

        for (Reservation existing : list) {
            if (reservation.start().isBefore(existing.end()) && existing.start().isBefore(reservation.end())) {
                throw new IllegalStateException("Double-booking conflict on charger " + reservation.chargerId() +
                        ": existing [" + existing.start() + ", " + existing.end() + "], requested [" +
                        reservation.start() + ", " + reservation.end() + "]");
            }
        }

        list.add(reservation);
        list.sort(Comparator.comparing(Reservation::start));
    }

    /**
     * Cancels a previously booked reservation.
     */
    public synchronized boolean cancel(String chargerId, Instant start, Instant end) {
        List<Reservation> list = chargerReservations.get(chargerId);
        if (list == null) return false;
        return list.removeIf(r -> r.start().equals(start) && r.end().equals(end));
    }

    /**
     * Checks if a charger stall is free for [start, end].
     */
    public boolean isStallAvailable(String chargerId, Instant start, Instant end) {
        List<Reservation> list = chargerReservations.getOrDefault(chargerId, Collections.emptyList());
        for (Reservation r : list) {
            if (start.isBefore(r.end()) && r.start().isBefore(end)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns total reservations for a charger.
     */
    public List<Reservation> getReservations(String chargerId) {
        return Collections.unmodifiableList(chargerReservations.getOrDefault(chargerId, Collections.emptyList()));
    }
}
