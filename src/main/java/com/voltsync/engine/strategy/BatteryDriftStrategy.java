package com.voltsync.engine.strategy;

import com.voltsync.domain.*;
import com.voltsync.engine.ChargerTimeline;
import com.voltsync.engine.ChargingCurve;
import com.voltsync.engine.PlanValidator;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Handles battery drift events when actual cab SoC is lower than planned.
 * Re-plans the remaining shift in < 2 seconds while minimizing assignment changes.
 */
public class BatteryDriftStrategy implements ReplanStrategy {

    private final String vehicleId;
    private final double actualSoc;
    private final Instant atTime;
    private final PlanValidator validator = new PlanValidator();

    public BatteryDriftStrategy(String vehicleId, double actualSoc, Instant atTime) {
        this.vehicleId = Objects.requireNonNull(vehicleId, "vehicleId must not be null");
        if (actualSoc < 0.0 || actualSoc > 100.0) {
            throw new IllegalArgumentException("actualSoc must be in [0, 100]");
        }
        this.actualSoc = actualSoc;
        this.atTime = Objects.requireNonNull(atTime, "atTime must not be null");
    }

    @Override
    public ReplanResult execute(ReplanContext context) {
        long startMs = System.currentTimeMillis();
        Plan plan = context.currentPlan();

        Map<String, List<Event>> newSchedules = new HashMap<>();
        for (Map.Entry<String, List<Event>> entry : plan.getCabSchedules().entrySet()) {
            newSchedules.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }

        Map<String, Vehicle> vehicleMap = context.vehicles().stream()
                .collect(Collectors.toMap(Vehicle::getId, Function.identity()));
        Map<String, Trip> tripMap = context.trips().stream()
                .collect(Collectors.toMap(Trip::getId, Function.identity()));

        Vehicle vehicle = vehicleMap.get(vehicleId);
        if (vehicle == null) {
            throw new IllegalArgumentException("Vehicle " + vehicleId + " not found in context");
        }

        List<Event> cabEvents = newSchedules.getOrDefault(vehicleId, new ArrayList<>());
        List<Event> pastEvents = new ArrayList<>();
        List<Event> futureEvents = new ArrayList<>();

        for (Event e : cabEvents) {
            if (!e.getEndTime().isAfter(atTime)) {
                pastEvents.add(e);
            } else {
                futureEvents.add(e);
            }
        }

        Set<String> modifiedVehicles = new HashSet<>();
        Map<String, String> reassignedTrips = new HashMap<>();
        List<UnassignedTrip> newlyUnassignedTrips = new ArrayList<>();
        List<ChargeEvent> newlyScheduledCharges = new ArrayList<>();
        List<ChargeEvent> canceledCharges = new ArrayList<>();

        // 1. Simulate forward from actualSoc at atTime
        double currentSoc = actualSoc;
        double batteryKWh = vehicle.getBatteryKWh();
        GeoLocation currentLoc = pastEvents.isEmpty() ? vehicle.getLocation() : pastEvents.get(pastEvents.size() - 1).getEndLocation();
        Instant availableTime = pastEvents.isEmpty() ? atTime : pastEvents.get(pastEvents.size() - 1).getEndTime();
        if (availableTime.isBefore(atTime)) {
            availableTime = atTime;
        }

        int violationIndex = -1;
        for (int i = 0; i < futureEvents.size(); i++) {
            Event e = futureEvents.get(i);
            if (currentSoc < (PlanValidator.BATTERY_FLOOR_PERCENT - 1e-4)) {
                violationIndex = i;
                break;
            }
            double after = e.applySoc(currentSoc, batteryKWh);
            if (after < (PlanValidator.BATTERY_FLOOR_PERCENT - 1e-4)) {
                violationIndex = i;
                break;
            }
            e.setSocBefore(currentSoc);
            e.setSocAfter(after);
            currentSoc = after;
            currentLoc = e.getEndLocation();
            availableTime = e.getEndTime();
        }

        if (violationIndex == -1) {
            // No violation occurs: simple state update
            List<Event> updatedEvents = new ArrayList<>(pastEvents);
            updatedEvents.addAll(futureEvents);
            newSchedules.put(vehicleId, updatedEvents);

            long duration = System.currentTimeMillis() - startMs;
            ReplanDiff diff = ReplanDiff.empty(plan.getPlanId(), plan.getVersion(), duration);
            ValidationReport vr = validator.validate(plan, context.vehicles(), context.hubs(), context.trips());
            return new ReplanResult(plan, diff, vr);
        }

        // Violation occurs!
        modifiedVehicles.add(vehicleId);

        // Keep valid events before violation
        List<Event> keptEvents = new ArrayList<>(pastEvents);
        for (int i = 0; i < violationIndex; i++) {
            keptEvents.add(futureEvents.get(i));
        }

        // Collect dropped events from violation index onward
        List<Trip> droppedTrips = new ArrayList<>();
        for (int i = violationIndex; i < futureEvents.size(); i++) {
            Event e = futureEvents.get(i);
            if (e instanceof TripEvent te) {
                Trip t = tripMap.get(te.getTripId());
                if (t != null) {
                    droppedTrips.add(t);
                }
            } else if (e instanceof ChargeEvent ce) {
                context.chargerTimeline().cancel(ce.getChargerId(), ce.getStartTime(), ce.getEndTime());
                canceledCharges.add(ce);
            }
        }

        // Compute cab state at the end of kept events
        if (!keptEvents.isEmpty()) {
            Event lastKept = keptEvents.get(keptEvents.size() - 1);
            currentLoc = lastKept.getEndLocation();
            availableTime = lastKept.getEndTime();
            currentSoc = lastKept.getSocAfter();
        } else {
            currentLoc = vehicle.getLocation();
            availableTime = atTime;
            currentSoc = actualSoc;
        }

        // Recovery (a): Try to insert a charge session for vehicleId and recover dropped trips
        Hub nearestHub = findNearestHub(currentLoc, context.hubs());
        if (nearestHub != null && !droppedTrips.isEmpty()) {
            double dhToHubDist = currentLoc.drivingDistanceKm(nearestHub.getLocation());
            Duration dhToHubDur = context.model().travelDuration(dhToHubDist, availableTime);
            Instant arriveHub = availableTime.plus(dhToHubDur);

            double eDh = context.model().calculateEnergy(dhToHubDist, availableTime);
            double socAtHub = currentSoc - (eDh / batteryKWh) * 100.0;

            if (socAtHub >= PlanValidator.BATTERY_FLOOR_PERCENT) {
                // Determine target trip
                Trip candidateTrip = droppedTrips.get(0);
                double dhHubToTrip = nearestHub.getLocation().drivingDistanceKm(candidateTrip.getStartLoc());
                Duration dhHubToTripDur = context.model().travelDuration(dhHubToTrip, candidateTrip.getStartTime());
                Instant mustDepartHub = candidateTrip.getStartTime().minus(dhHubToTripDur);

                if (arriveHub.isBefore(mustDepartHub)) {
                    double targetSoc = 80.0;
                    Duration chargeDur = ChargingCurve.timeToCharge(socAtHub, targetSoc, batteryKWh, 50.0);

                    if (arriveHub.plus(chargeDur).isBefore(mustDepartHub) || arriveHub.plus(chargeDur).equals(mustDepartHub)) {
                        Optional<ChargerTimeline.AvailableSlot> slotOpt = context.chargerTimeline().findSlot(
                                nearestHub, arriveHub, mustDepartHub, chargeDur, context.tariff()
                        );

                        if (slotOpt.isPresent()) {
                            ChargerTimeline.AvailableSlot slot = slotOpt.get();
                            // Deadhead to hub
                            if (dhToHubDist > PlanValidator.LOCATION_TOLERANCE_KM) {
                                keptEvents.add(new DeadheadEvent(
                                        availableTime, arriveHub, currentLoc, nearestHub.getLocation(),
                                        dhToHubDist, eDh, DeadheadPurpose.TO_CHARGING_HUB, currentSoc, socAtHub
                                ));
                            }

                            // Charge session
                            Instant chargeStart = slot.start();
                            Instant chargeEnd = chargeStart.plus(chargeDur);
                            double energyAdded = ChargingCurve.energyAddedKWh(socAtHub, targetSoc, batteryKWh);
                            double cost = context.tariff().calculateCost(chargeStart, chargeEnd, energyAdded);

                            ChargeEvent newCharge = new ChargeEvent(
                                    nearestHub.getId(), slot.chargerId(), chargeStart, chargeEnd,
                                    nearestHub.getLocation(), socAtHub, targetSoc, cost, energyAdded, socAtHub, targetSoc
                            );
                            keptEvents.add(newCharge);
                            newlyScheduledCharges.add(newCharge);
                            context.chargerTimeline().book(new ChargerTimeline.Reservation(
                                    nearestHub.getId(), slot.chargerId(), vehicleId, chargeStart, chargeEnd
                            ));

                            // Deadhead to candidate trip
                            Instant dhStart = candidateTrip.getStartTime().minus(dhHubToTripDur);
                            if (dhStart.isBefore(chargeEnd)) {
                                dhStart = chargeEnd;
                            }
                            double eDh2 = context.model().calculateEnergy(dhHubToTrip, dhStart);
                            double socAfterDh = targetSoc - (eDh2 / batteryKWh) * 100.0;

                            if (dhHubToTrip > PlanValidator.LOCATION_TOLERANCE_KM) {
                                keptEvents.add(new DeadheadEvent(
                                        dhStart, candidateTrip.getStartTime(), nearestHub.getLocation(), candidateTrip.getStartLoc(),
                                        dhHubToTrip, eDh2, DeadheadPurpose.TO_TRIP_ORIGIN, targetSoc, socAfterDh
                                ));
                            }

                            // Retain candidate trip
                            double eTrip = context.model().calculateEnergy(candidateTrip.getDistanceKm(), candidateTrip.getStartTime());
                            Duration tripDur = context.model().travelDuration(candidateTrip.getDistanceKm(), candidateTrip.getStartTime());
                            Instant tripEnd = candidateTrip.getStartTime().plus(tripDur);
                            double socAfterTrip = socAfterDh - (eTrip / batteryKWh) * 100.0;

                            keptEvents.add(new TripEvent(
                                    candidateTrip.getId(), candidateTrip.getStartTime(), tripEnd,
                                    candidateTrip.getStartLoc(), candidateTrip.getEndLoc(),
                                    candidateTrip.getDistanceKm(), eTrip, socAfterDh, socAfterTrip
                            ));

                            droppedTrips.remove(0);
                        }
                    }
                }
            }
        }

        newSchedules.put(vehicleId, keptEvents);

        // Recovery (b): Reassign remaining dropped trips to other cabs via greedy insertion
        for (Trip trip : droppedTrips) {
            boolean reassigned = false;

            for (Map.Entry<String, List<Event>> entry : newSchedules.entrySet()) {
                String candidateCabId = entry.getKey();
                if (candidateCabId.equals(vehicleId)) continue;

                Vehicle candidateCab = vehicleMap.get(candidateCabId);
                List<Event> events = entry.getValue();

                // Check if candidate cab can take trip at the end of its schedule
                GeoLocation cabEndLoc = events.isEmpty() ? candidateCab.getLocation() : events.get(events.size() - 1).getEndLocation();
                Instant cabEndTime = events.isEmpty() ? atTime : events.get(events.size() - 1).getEndTime();
                double cabSoc = events.isEmpty() ? candidateCab.getSocPct() : events.get(events.size() - 1).getSocAfter();

                double dhDist = cabEndLoc.drivingDistanceKm(trip.getStartLoc());
                Duration dhDur = context.model().travelDuration(dhDist, cabEndTime);
                Instant arrival = cabEndTime.plus(dhDur);

                if (!arrival.isAfter(trip.getStartTime())) {
                    double eDh = context.model().calculateEnergy(dhDist, cabEndTime);
                    double eTrip = context.model().calculateEnergy(trip.getDistanceKm(), trip.getStartTime());
                    Duration tripDur = context.model().travelDuration(trip.getDistanceKm(), trip.getStartTime());
                    Instant tripEnd = trip.getStartTime().plus(tripDur);

                    double totalE = eDh + eTrip;
                    double socDrop = (totalE / candidateCab.getBatteryKWh()) * 100.0;

                    if (cabSoc - socDrop >= PlanValidator.BATTERY_FLOOR_PERCENT && !tripEnd.isAfter(trip.getLatestFinish())) {
                        // Insert deadhead and trip
                        Instant dhStart = trip.getStartTime().minus(dhDur);
                        if (dhStart.isBefore(cabEndTime)) {
                            dhStart = cabEndTime;
                        }

                        if (dhDist > PlanValidator.LOCATION_TOLERANCE_KM) {
                            events.add(new DeadheadEvent(
                                    dhStart, trip.getStartTime(), cabEndLoc, trip.getStartLoc(),
                                    dhDist, eDh, DeadheadPurpose.TO_TRIP_ORIGIN, cabSoc, cabSoc - (eDh / candidateCab.getBatteryKWh()) * 100.0
                            ));
                        }

                        double socBeforeTrip = cabSoc - (eDh / candidateCab.getBatteryKWh()) * 100.0;
                        double socAfterTrip = socBeforeTrip - (eTrip / candidateCab.getBatteryKWh()) * 100.0;

                        events.add(new TripEvent(
                                trip.getId(), trip.getStartTime(), tripEnd, trip.getStartLoc(), trip.getEndLoc(),
                                trip.getDistanceKm(), eTrip, socBeforeTrip, socAfterTrip
                        ));

                        reassignedTrips.put(trip.getId(), candidateCabId);
                        modifiedVehicles.add(candidateCabId);
                        reassigned = true;
                        break;
                    }
                }
            }

            if (!reassigned) {
                newlyUnassignedTrips.add(new UnassignedTrip(trip.getId(), UnassignedReason.INSUFFICIENT_CHARGE,
                        "Battery drift on cab " + vehicleId));
            }
        }

        // Compute total cost of new schedules
        double newCost = 0.0;
        for (List<Event> evList : newSchedules.values()) {
            for (Event e : evList) {
                if (e instanceof ChargeEvent ce) {
                    newCost += ce.getCost();
                }
            }
        }

        List<UnassignedTrip> allUnassigned = new ArrayList<>(plan.getUnassigned());
        allUnassigned.addAll(newlyUnassignedTrips);

        int changedAssignmentsCount = reassignedTrips.size() + newlyUnassignedTrips.size();
        long replanDurationMs = System.currentTimeMillis() - startMs;

        long nextVersion = plan.getVersion() + 1;
        Plan updatedPlan = new Plan(
                plan.getPlanId(), nextVersion, newSchedules, allUnassigned,
                newCost, plan.getLowerBound(), new HashMap<>(plan.getStats())
        );
        updatedPlan.putStat("replanDurationMs", replanDurationMs);
        updatedPlan.putStat("changedAssignmentsCount", changedAssignmentsCount);

        ReplanDiff diff = new ReplanDiff(
                plan.getPlanId(), plan.getVersion(), nextVersion,
                changedAssignmentsCount, modifiedVehicles, reassignedTrips,
                newlyUnassignedTrips, newlyScheduledCharges, canceledCharges, replanDurationMs
        );

        ValidationReport vr = validator.validate(updatedPlan, context.vehicles(), context.hubs(), context.trips());
        return new ReplanResult(updatedPlan, diff, vr);
    }

    private Hub findNearestHub(GeoLocation loc, List<Hub> hubs) {
        Hub best = null;
        double minD = Double.MAX_VALUE;
        for (Hub h : hubs) {
            double d = loc.drivingDistanceKm(h.getLocation());
            if (d < minD) {
                minD = d;
                best = h;
            }
        }
        return best;
    }
}
