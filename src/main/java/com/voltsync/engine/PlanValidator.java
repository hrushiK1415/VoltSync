package com.voltsync.engine;

import com.voltsync.domain.*;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Independent validator that verifies all hard constraints and logical integrity of a Plan.
 * Does NOT reuse solver heuristic code to guarantee unbiased verification.
 */
public class PlanValidator {

    public static final double BATTERY_FLOOR_PERCENT = 15.0;
    public static final double LOCATION_TOLERANCE_KM = 0.05; // 50 meters
    public static final double EPSILON = 1e-4;

    /**
     * Validates a complete plan against the input fleet, hubs, and trips.
     */
    public ValidationReport validate(Plan plan, List<Vehicle> vehicles, List<Hub> hubs, List<Trip> trips) {
        Objects.requireNonNull(plan, "plan must not be null");
        Objects.requireNonNull(vehicles, "vehicles must not be null");
        Objects.requireNonNull(hubs, "hubs must not be null");
        Objects.requireNonNull(trips, "trips must not be null");

        Map<String, Vehicle> vehicleMap = vehicles.stream()
                .collect(Collectors.toMap(Vehicle::getId, Function.identity()));
        Map<String, Hub> hubMap = hubs.stream()
                .collect(Collectors.toMap(Hub::getId, Function.identity()));
        Map<String, Trip> tripMap = trips.stream()
                .collect(Collectors.toMap(Trip::getId, Function.identity()));

        List<ValidationViolation> violations = new ArrayList<>();

        validateVehicleSchedules(plan, vehicleMap, tripMap, violations);
        validateChargerCapacities(plan, hubMap, violations);
        validateTripAssignments(plan, tripMap, violations);

        boolean isValid = violations.isEmpty();
        return new ValidationReport(isValid, violations);
    }

    private void validateVehicleSchedules(Plan plan,
                                          Map<String, Vehicle> vehicleMap,
                                          Map<String, Trip> tripMap,
                                          List<ValidationViolation> violations) {
        for (Map.Entry<String, List<Event>> entry : plan.getCabSchedules().entrySet()) {
            String vehicleId = entry.getKey();
            List<Event> events = entry.getValue();

            Vehicle vehicle = vehicleMap.get(vehicleId);
            if (vehicle == null) {
                violations.add(new ValidationViolation("UNKNOWN_VEHICLE",
                        "Vehicle " + vehicleId + " has a schedule but is not registered in the fleet", vehicleId));
                continue;
            }

            if (events == null || events.isEmpty()) {
                continue;
            }

            double currentSoc = vehicle.getSocPct();
            double batteryKWh = vehicle.getBatteryKWh();
            GeoLocation currentLocation = vehicle.getLocation();
            Instant lastEndTime = null;

            for (int i = 0; i < events.size(); i++) {
                Event event = events.get(i);

                if (event.getEndTime().isBefore(event.getStartTime())) {
                    violations.add(new ValidationViolation("INVALID_EVENT_DURATION",
                            "Event " + i + " ends before it starts: " + event.getStartTime() + " -> " + event.getEndTime(),
                            vehicleId, event.getStartTime()));
                }

                if (lastEndTime != null && event.getStartTime().isBefore(lastEndTime)) {
                    violations.add(new ValidationViolation("OVERLAPPING_EVENTS",
                            "Event " + i + " starts at " + event.getStartTime() + " before previous event ended at " + lastEndTime,
                            vehicleId, event.getStartTime()));
                }

                // Verify spatial continuity: cab cannot teleport between distinct coordinates without a deadhead
                double distFromPrev = currentLocation.haversineDistanceKm(event.getStartLocation());
                if (distFromPrev > LOCATION_TOLERANCE_KM) {
                    violations.add(new ValidationViolation("LOCATION_DISCONTINUITY",
                            "Vehicle location discontinuity at event " + i + ": expected at " + currentLocation +
                                    " but event starts at " + event.getStartLocation() + " (gap: " + String.format("%.2f", distFromPrev) + " km)",
                            vehicleId, event.getStartTime()));
                }

                if (currentSoc < (BATTERY_FLOOR_PERCENT - EPSILON)) {
                    violations.add(new ValidationViolation("FLOOR_BREACH",
                            "Vehicle SoC dropped to " + String.format("%.2f", currentSoc) + "% at start of event " + i +
                                    " (" + event.getType() + ") at " + event.getStartTime() + " (floor: " + BATTERY_FLOOR_PERCENT + "%)",
                            vehicleId, event.getStartTime()));
                }

                double socAfter = event.applySoc(currentSoc, batteryKWh);

                if (socAfter < (BATTERY_FLOOR_PERCENT - EPSILON)) {
                    violations.add(new ValidationViolation("FLOOR_BREACH",
                            "Vehicle SoC dropped to " + String.format("%.2f", socAfter) + "% after event " + i +
                                    " (" + event.getType() + ") at " + event.getEndTime() + " (floor: " + BATTERY_FLOOR_PERCENT + "%)",
                            vehicleId, event.getEndTime()));
                }

                if (event instanceof TripEvent tripEvent) {
                    Trip trip = tripMap.get(tripEvent.getTripId());
                    if (trip == null) {
                        violations.add(new ValidationViolation("UNKNOWN_TRIP_ASSIGNED",
                                "Trip " + tripEvent.getTripId() + " in schedule not found in input trips", vehicleId, tripEvent.getStartTime()));
                    } else {
                        if (!tripEvent.getStartTime().equals(trip.getStartTime())) {
                            violations.add(new ValidationViolation("TRIP_NOT_ON_TIME",
                                    "Trip " + trip.getId() + " must start on time at " + trip.getStartTime() +
                                            ", but scheduled at " + tripEvent.getStartTime(),
                                    trip.getId(), tripEvent.getStartTime()));
                        }
                        if (tripEvent.getEndTime().isAfter(trip.getLatestFinish())) {
                            violations.add(new ValidationViolation("LATE_TRIP",
                                    "Trip " + trip.getId() + " finishes at " + tripEvent.getEndTime() +
                                            " which is after latestFinish " + trip.getLatestFinish(),
                                    trip.getId(), tripEvent.getEndTime()));
                        }
                    }
                }

                currentSoc = socAfter;
                currentLocation = event.getEndLocation();
                lastEndTime = event.getEndTime();
            }
        }
    }

    private void validateChargerCapacities(Plan plan,
                                           Map<String, Hub> hubMap,
                                           List<ValidationViolation> violations) {
        // Collect all charge events per hub
        Map<String, List<ChargeEvent>> hubChargeEvents = new HashMap<>();
        // Collect charge events per specific charger stall to verify single-stall exclusivity
        Map<String, List<ChargeEvent>> chargerStallEvents = new HashMap<>();

        for (Map.Entry<String, List<Event>> entry : plan.getCabSchedules().entrySet()) {
            for (Event event : entry.getValue()) {
                if (event instanceof ChargeEvent chargeEvent) {
                    hubChargeEvents.computeIfAbsent(chargeEvent.getHubId(), k -> new ArrayList<>())
                            .add(chargeEvent);
                    chargerStallEvents.computeIfAbsent(chargeEvent.getChargerId(), k -> new ArrayList<>())
                            .add(chargeEvent);
                }
            }
        }

        for (Map.Entry<String, List<ChargeEvent>> entry : hubChargeEvents.entrySet()) {
            String hubId = entry.getKey();
            List<ChargeEvent> sessions = entry.getValue();

            Hub hub = hubMap.get(hubId);
            if (hub == null) {
                violations.add(new ValidationViolation("UNKNOWN_HUB",
                        "Charging scheduled at hub " + hubId + " which does not exist", hubId));
                continue;
            }

            List<SweepPoint> points = new ArrayList<>();
            for (ChargeEvent session : sessions) {
                points.add(new SweepPoint(session.getStartTime(), +1, session));
                points.add(new SweepPoint(session.getEndTime(), -1, session));
            }

            // Sweep-line ordering: earliest timestamp first; on exact tie, departures (delta -1)
            // precede arrivals (+1) so leaving vehicles free stalls at that exact instant
            points.sort((a, b) -> {
                int cmp = a.time.compareTo(b.time);
                if (cmp != 0) return cmp;
                return Integer.compare(a.delta, b.delta);
            });

            long maxChargersUp = hub.activeChargersCount();
            int currentConcurrent = 0;

            for (SweepPoint sp : points) {
                currentConcurrent += sp.delta;
                if (currentConcurrent > maxChargersUp) {
                    violations.add(new ValidationViolation("CHARGER_OVERBOOKING",
                            "Hub " + hubId + " exceeded charger capacity at " + sp.time +
                                    ": " + currentConcurrent + " concurrent charging sessions but only " +
                                    maxChargersUp + " chargers UP",
                            hubId, sp.time));
                    break;
                }
            }
        }

        for (Map.Entry<String, List<ChargeEvent>> entry : chargerStallEvents.entrySet()) {
            String chargerId = entry.getKey();
            List<ChargeEvent> sessions = entry.getValue();

            for (ChargeEvent session : sessions) {
                Hub hub = hubMap.get(session.getHubId());
                if (hub != null) {
                    Charger charger = hub.getChargers().stream()
                            .filter(c -> c.getId().equals(chargerId))
                            .findFirst()
                            .orElse(null);
                    if (charger != null && !charger.isUp()) {
                        violations.add(new ValidationViolation("BROKEN_CHARGER_USED",
                                "Charger " + chargerId + " at hub " + session.getHubId() +
                                        " is marked DOWN but assigned to a charging session at " + session.getStartTime(),
                                chargerId, session.getStartTime()));
                    }
                }
            }

            sessions.sort(Comparator.comparing(Event::getStartTime));
            for (int i = 0; i < sessions.size() - 1; i++) {
                ChargeEvent s1 = sessions.get(i);
                ChargeEvent s2 = sessions.get(i + 1);
                if (s1.getEndTime().isAfter(s2.getStartTime())) {
                    violations.add(new ValidationViolation("CHARGER_STALL_CONFLICT",
                            "Charger " + chargerId + " double-booked: session ending at " +
                                    s1.getEndTime() + " overlaps with next session starting at " + s2.getStartTime(),
                            chargerId, s2.getStartTime()));
                }
            }
        }
    }

    private void validateTripAssignments(Plan plan,
                                         Map<String, Trip> tripMap,
                                         List<ValidationViolation> violations) {
        Map<String, Integer> assignedTripCounts = new HashMap<>();

        for (Map.Entry<String, List<Event>> entry : plan.getCabSchedules().entrySet()) {
            for (Event event : entry.getValue()) {
                if (event instanceof TripEvent tripEvent) {
                    assignedTripCounts.merge(tripEvent.getTripId(), 1, Integer::sum);
                }
            }
        }

        for (Map.Entry<String, Integer> entry : assignedTripCounts.entrySet()) {
            if (entry.getValue() > 1) {
                violations.add(new ValidationViolation("DUPLICATE_TRIP_ASSIGNMENT",
                        "Trip " + entry.getKey() + " is assigned " + entry.getValue() + " times across cab schedules",
                        entry.getKey()));
            }
        }

        Set<String> unassignedTripIds = new HashSet<>();
        for (UnassignedTrip unassigned : plan.getUnassigned()) {
            String tripId = unassigned.tripId();
            if (assignedTripCounts.containsKey(tripId)) {
                violations.add(new ValidationViolation("CONTRADICTORY_TRIP_ASSIGNMENT",
                        "Trip " + tripId + " is both assigned to a cab and marked unassigned", tripId));
            }
            if (unassigned.reason() == null) {
                violations.add(new ValidationViolation("MISSING_UNASSIGNED_REASON",
                        "Unassigned trip " + tripId + " has no explanation reason", tripId));
            }
            unassignedTripIds.add(tripId);
        }

        for (String tripId : tripMap.keySet()) {
            boolean isAssigned = assignedTripCounts.containsKey(tripId);
            boolean isUnassigned = unassignedTripIds.contains(tripId);

            if (!isAssigned && !isUnassigned) {
                violations.add(new ValidationViolation("UNACCOUNTED_TRIP",
                        "Trip " + tripId + " is neither assigned to a cab nor marked unassigned", tripId));
            }
        }
    }

    private record SweepPoint(Instant time, int delta, ChargeEvent session) {
    }
}
