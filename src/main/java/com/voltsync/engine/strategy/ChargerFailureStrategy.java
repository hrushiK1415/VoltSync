package com.voltsync.engine.strategy;

import com.voltsync.domain.*;
import com.voltsync.engine.ChargerTimeline;
import com.voltsync.engine.PlanValidator;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Handles physical charger breakdown events.
 * Relocates affected charging sessions to alternative chargers at the same hub or nearby hubs,
 * preserving existing trip assignments whenever possible.
 */
public class ChargerFailureStrategy implements ReplanStrategy {

    private final String brokenChargerId;
    private final Instant atTime;
    private final PlanValidator validator = new PlanValidator();

    public ChargerFailureStrategy(String brokenChargerId, Instant atTime) {
        this.brokenChargerId = Objects.requireNonNull(brokenChargerId, "brokenChargerId must not be null");
        this.atTime = Objects.requireNonNull(atTime, "atTime must not be null");
    }

    @Override
    public ReplanResult execute(ReplanContext context) {
        long startMs = System.currentTimeMillis();
        Plan plan = context.currentPlan();

        // Mark charger as DOWN
        for (Hub h : context.hubs()) {
            for (Charger c : h.getChargers()) {
                if (c.getId().equals(brokenChargerId)) {
                    c.setStatus(ChargerStatus.DOWN);
                }
            }
        }

        Map<String, List<Event>> newSchedules = new HashMap<>();
        for (Map.Entry<String, List<Event>> entry : plan.getCabSchedules().entrySet()) {
            newSchedules.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }

        Map<String, Vehicle> vehicleMap = context.vehicles().stream()
                .collect(Collectors.toMap(Vehicle::getId, Function.identity()));
        Map<String, Hub> hubMap = context.hubs().stream()
                .collect(Collectors.toMap(Hub::getId, Function.identity()));

        Set<String> modifiedVehicles = new HashSet<>();
        Map<String, String> reassignedTrips = new HashMap<>();
        List<UnassignedTrip> newlyUnassignedTrips = new ArrayList<>();
        List<ChargeEvent> newlyScheduledCharges = new ArrayList<>();
        List<ChargeEvent> canceledCharges = new ArrayList<>();

        // Find affected charge sessions across all vehicles
        for (Map.Entry<String, List<Event>> entry : newSchedules.entrySet()) {
            String vehicleId = entry.getKey();
            List<Event> events = entry.getValue();

            for (int i = 0; i < events.size(); i++) {
                Event e = events.get(i);
                if (e instanceof ChargeEvent ce && ce.getChargerId().equals(brokenChargerId)) {
                    if (ce.getEndTime().isAfter(atTime)) {
                        // Affected future charging session!
                        modifiedVehicles.add(vehicleId);
                        context.chargerTimeline().cancel(brokenChargerId, ce.getStartTime(), ce.getEndTime());
                        canceledCharges.add(ce);

                        Hub hub = hubMap.get(ce.getHubId());
                        boolean relocated = false;

                        // Step (a): Try another UP charger at the SAME hub
                        if (hub != null) {
                            for (Charger alternative : hub.getChargers()) {
                                if (alternative.isUp() && !alternative.getId().equals(brokenChargerId)) {
                                    if (context.chargerTimeline().isStallAvailable(alternative.getId(), ce.getStartTime(), ce.getEndTime())) {
                                        // Found stall at same hub!
                                        ChargeEvent replacement = new ChargeEvent(
                                                ce.getHubId(), alternative.getId(), ce.getStartTime(), ce.getEndTime(),
                                                ce.getStartLocation(), ce.getSocFrom(), ce.getSocTo(),
                                                ce.getCost(), ce.getEnergyAddedKWh(), ce.getSocBefore(), ce.getSocAfter()
                                        );
                                        events.set(i, replacement);
                                        newlyScheduledCharges.add(replacement);
                                        context.chargerTimeline().book(new ChargerTimeline.Reservation(
                                                ce.getHubId(), alternative.getId(), vehicleId, ce.getStartTime(), ce.getEndTime()
                                        ));
                                        relocated = true;
                                        break;
                                    }
                                }
                            }
                        }

                        // Step (b): If not relocated at same hub, try finding any slot in the gap
                        if (!relocated && hub != null) {
                            Duration chargeDur = Duration.between(ce.getStartTime(), ce.getEndTime());
                            Optional<ChargerTimeline.AvailableSlot> slotOpt = context.chargerTimeline().findSlot(
                                    hub, ce.getStartTime(), ce.getEndTime().plus(Duration.ofMinutes(15)), chargeDur, context.tariff()
                            );
                            if (slotOpt.isPresent()) {
                                ChargerTimeline.AvailableSlot slot = slotOpt.get();
                                ChargeEvent replacement = new ChargeEvent(
                                        slot.hubId(), slot.chargerId(), slot.start(), slot.end(),
                                        ce.getStartLocation(), ce.getSocFrom(), ce.getSocTo(),
                                        ce.getCost(), ce.getEnergyAddedKWh(), ce.getSocBefore(), ce.getSocAfter()
                                );
                                events.set(i, replacement);
                                newlyScheduledCharges.add(replacement);
                                context.chargerTimeline().book(new ChargerTimeline.Reservation(
                                        slot.hubId(), slot.chargerId(), vehicleId, slot.start(), slot.end()
                                ));
                                relocated = true;
                            }
                        }

                        if (!relocated) {
                            // Step (c): If charge cannot be relocated, drop subsequent trips of this cab
                            List<Event> remainingEvents = new ArrayList<>(events.subList(0, i));
                            for (int k = i; k < events.size(); k++) {
                                Event dropped = events.get(k);
                                if (dropped instanceof TripEvent te) {
                                    newlyUnassignedTrips.add(new UnassignedTrip(
                                            te.getTripId(), UnassignedReason.CHARGER_CONGESTION,
                                            "Charger " + brokenChargerId + " broke and no alternative charger was available"
                                    ));
                                } else if (dropped instanceof ChargeEvent otherCe) {
                                    context.chargerTimeline().cancel(otherCe.getChargerId(), otherCe.getStartTime(), otherCe.getEndTime());
                                    canceledCharges.add(otherCe);
                                }
                            }
                            events.clear();
                            events.addAll(remainingEvents);
                            break;
                        }
                    }
                }
            }
        }

        // Recompute total cost
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
}
