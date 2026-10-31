package com.voltsync.engine;

import com.voltsync.domain.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * High-performance heuristic solver combining greedy insertion with time-boxed local search.
 * Satisfies lexicographic objective:
 * 1) Minimize trips sent to backup (unassigned) cabs.
 * 2) Minimize charging cost (preferring off-peak tariff windows).
 */
public class GreedyLocalSearchSolver implements Solver {

    private final PlanValidator validator = new PlanValidator();
    private final LowerBoundCalculator lbCalculator = new LowerBoundCalculator();

    @Override
    public Plan solve(SolverInput input) {
        long startTimeMs = System.currentTimeMillis();
        long deadlineMs = startTimeMs + input.timeBudget().toMillis();

        List<Trip> sortedTrips = new ArrayList<>(input.trips());
        sortedTrips.sort(Comparator.comparing(Trip::getStartTime));

        // 1. Initialize ChargerTimeline
        ChargerTimeline chargerTimeline = new ChargerTimeline();

        // 2. Initialize Cab States
        List<CabState> cabStates = new ArrayList<>();
        for (Vehicle v : input.vehicles()) {
            cabStates.add(new CabState(v));
        }

        List<UnassignedTrip> unassignedTrips = new ArrayList<>();

        // Precompute lower bound
        LowerBoundCalculator.LowerBoundResult lb = lbCalculator.calculate(
                input.vehicles(), input.hubs(), input.trips(), input.tariff(), input.consumptionModel()
        );

        // 3. Greedy Construction Phase
        for (Trip trip : sortedTrips) {
            CandidateOption bestOption = null;
            UnassignedReason primaryFailureReason = UnassignedReason.NO_CAB_IN_TIME;
            boolean hadCongestion = false;
            boolean hadInsufficientCharge = false;

            Duration tripDur = input.consumptionModel().travelDuration(trip.getDistanceKm(), trip.getStartTime());
            Instant tripEnd = trip.getStartTime().plus(tripDur);

            // Check trip self-feasibility
            if (tripEnd.isAfter(trip.getLatestFinish())) {
                unassignedTrips.add(new UnassignedTrip(trip.getId(), UnassignedReason.TIME_WINDOW_INFEASIBLE,
                        "Trip duration (" + tripDur.toMinutes() + "m) exceeds available window"));
                continue;
            }

            for (CabState cab : cabStates) {
                CandidateEvaluation eval = evaluateCabForTrip(cab, trip, tripDur, tripEnd, input, chargerTimeline);
                if (eval.isFeasible()) {
                    if (bestOption == null || eval.option().score() < bestOption.score()) {
                        bestOption = eval.option();
                    }
                } else {
                    if (eval.failureReason() == UnassignedReason.CHARGER_CONGESTION) {
                        hadCongestion = true;
                    } else if (eval.failureReason() == UnassignedReason.INSUFFICIENT_CHARGE) {
                        hadInsufficientCharge = true;
                    }
                }
            }

            if (bestOption != null) {
                // Apply chosen option to cab
                applyOptionToCab(bestOption, chargerTimeline);
            } else {
                if (hadCongestion) {
                    primaryFailureReason = UnassignedReason.CHARGER_CONGESTION;
                } else if (hadInsufficientCharge) {
                    primaryFailureReason = UnassignedReason.INSUFFICIENT_CHARGE;
                }
                unassignedTrips.add(new UnassignedTrip(trip.getId(), primaryFailureReason));
            }
        }

        // Build greedy plan
        Plan currentPlan = buildPlanFromCabStates(input.planId(), 1, cabStates, unassignedTrips, lb, input.tariff());

        // 4. Time-Boxed Local Search Phase
        boolean partial = false;
        long remainingTime = deadlineMs - System.currentTimeMillis();
        if (remainingTime > 100) {
            currentPlan = runLocalSearch(currentPlan, input, chargerTimeline, deadlineMs);
            if (System.currentTimeMillis() >= deadlineMs) {
                partial = true;
            }
        }

        long totalSolveTime = System.currentTimeMillis() - startTimeMs;
        currentPlan.putStat("solveTimeMs", totalSolveTime);
        currentPlan.putStat("partial", partial);
        currentPlan.putStat("unassignedCount", currentPlan.getUnassigned().size());

        return currentPlan;
    }

    private CandidateEvaluation evaluateCabForTrip(CabState cab,
                                                   Trip trip,
                                                   Duration tripDur,
                                                   Instant tripEnd,
                                                   SolverInput input,
                                                   ChargerTimeline chargerTimeline) {
        ConsumptionModel model = input.consumptionModel();

        // Check if cab can reach trip origin in time
        double dhToOriginDist = cab.currentLocation.drivingDistanceKm(trip.getStartLoc());
        Duration dhToOriginDur = model.travelDuration(dhToOriginDist, cab.availableTime);
        Instant earliestArrival = cab.availableTime.plus(dhToOriginDur);

        if (earliestArrival.isAfter(trip.getStartTime())) {
            return CandidateEvaluation.failed(UnassignedReason.NO_CAB_IN_TIME);
        }

        // Energy calculations
        double eDh = model.calculateEnergy(dhToOriginDist, cab.availableTime);
        double eTrip = model.calculateEnergy(trip.getDistanceKm(), trip.getStartTime());

        // Reserve to nearest hub from trip end
        Hub nearestHubFromTrip = findNearestHub(trip.getEndLoc(), input.hubs());
        double reserveDist = nearestHubFromTrip != null ? trip.getEndLoc().drivingDistanceKm(nearestHubFromTrip.getLocation()) : 0.0;
        double eReserve = model.calculateEnergy(reserveDist, tripEnd);

        double totalEnergyNoCharge = eDh + eTrip + eReserve;
        double socDropNoCharge = (totalEnergyNoCharge / cab.vehicle.getBatteryKWh()) * 100.0;

        // Path A: Feasible directly without intermediate charging
        if (cab.currentSoc - socDropNoCharge >= PlanValidator.BATTERY_FLOOR_PERCENT) {
            double score = dhToOriginDist * 1.0;
            CandidateOption opt = new CandidateOption(cab, trip, false, null, null, null,
                    dhToOriginDist, dhToOriginDur, 0.0, Duration.ZERO, 0.0, score);
            return CandidateEvaluation.success(opt);
        }

        // Path B: Try inserting a charge session at a hub before the trip
        for (Hub hub : input.hubs()) {
            double dhToHubDist = cab.currentLocation.drivingDistanceKm(hub.getLocation());
            Duration dhToHubDur = model.travelDuration(dhToHubDist, cab.availableTime);
            Instant arriveHub = cab.availableTime.plus(dhToHubDur);

            double dhHubToOriginDist = hub.getLocation().drivingDistanceKm(trip.getStartLoc());
            Duration dhHubToOriginDur = model.travelDuration(dhHubToOriginDist, trip.getStartTime());
            Instant mustLeaveHub = trip.getStartTime().minus(dhHubToOriginDur);

            if (arriveHub.isBefore(mustLeaveHub)) {
                double eToHub = model.calculateEnergy(dhToHubDist, cab.availableTime);
                double socAtHub = cab.currentSoc - (eToHub / cab.vehicle.getBatteryKWh()) * 100.0;

                if (socAtHub < PlanValidator.BATTERY_FLOOR_PERCENT) {
                    continue; // cannot even reach hub safely
                }

                // Energy required after charging: Hub -> Origin -> Trip -> Nearest Hub
                double eAfterCharge = model.calculateEnergy(dhHubToOriginDist, mustLeaveHub) + eTrip + eReserve;
                double socNeededAfterCharge = PlanValidator.BATTERY_FLOOR_PERCENT + (eAfterCharge / cab.vehicle.getBatteryKWh()) * 100.0 + 5.0; // 5% buffer
                double targetSoc = Math.min(85.0, Math.max(socAtHub + 15.0, socNeededAfterCharge));

                if (targetSoc > 100.0) {
                    continue;
                }

                // Average charger power for initial estimate
                double estPowerKW = 50.0;
                Duration chargeDur = ChargingCurve.timeToCharge(socAtHub, targetSoc, cab.vehicle.getBatteryKWh(), estPowerKW);

                if (arriveHub.plus(chargeDur).isAfter(mustLeaveHub)) {
                    continue; // not enough time to charge
                }

                Optional<ChargerTimeline.AvailableSlot> slotOpt = chargerTimeline.findSlot(
                        hub, arriveHub, mustLeaveHub, chargeDur, input.tariff()
                );

                if (slotOpt.isPresent()) {
                    ChargerTimeline.AvailableSlot slot = slotOpt.get();
                    // Refine charging duration with exact charger power
                    Duration exactDur = ChargingCurve.timeToCharge(socAtHub, targetSoc, cab.vehicle.getBatteryKWh(), slot.powerKW());
                    Instant chargeStart = slot.start();
                    Instant chargeEnd = chargeStart.plus(exactDur);

                    if (!chargeEnd.isAfter(mustLeaveHub)) {
                        double energyAdded = ChargingCurve.energyAddedKWh(socAtHub, targetSoc, cab.vehicle.getBatteryKWh());
                        double cost = input.tariff().calculateCost(chargeStart, chargeEnd, energyAdded);

                        double score = (dhToHubDist + dhHubToOriginDist) * 1.0 + cost * 5.0;

                        CandidateOption opt = new CandidateOption(
                                cab, trip, true, hub, slot, exactDur,
                                dhToHubDist, dhToHubDur, dhHubToOriginDist, dhHubToOriginDur,
                                cost, score
                        );
                        return CandidateEvaluation.success(opt);
                    }
                } else {
                    return CandidateEvaluation.failed(UnassignedReason.CHARGER_CONGESTION);
                }
            }
        }

        return CandidateEvaluation.failed(UnassignedReason.INSUFFICIENT_CHARGE);
    }

    private void applyOptionToCab(CandidateOption opt, ChargerTimeline chargerTimeline) {
        CabState cab = opt.cab();
        Trip trip = opt.trip();
        double batteryKWh = cab.vehicle.getBatteryKWh();

        if (opt.requiresCharging()) {
            Hub hub = opt.hub();
            ChargerTimeline.AvailableSlot slot = opt.slot();

            // 1. Deadhead to hub (if needed)
            if (opt.dhToHubDist() > PlanValidator.LOCATION_TOLERANCE_KM) {
                Instant dhStart = cab.availableTime;
                Instant dhEnd = dhStart.plus(opt.dhToHubDur());
                double eDh = (opt.dhToHubDist() * cab.vehicle.getConsumptionKWhPerKm());
                double socBefore = cab.currentSoc;
                double socAfter = Math.max(0.0, socBefore - (eDh / batteryKWh) * 100.0);

                cab.events.add(new DeadheadEvent(dhStart, dhEnd, cab.currentLocation, hub.getLocation(),
                        opt.dhToHubDist(), eDh, DeadheadPurpose.TO_CHARGING_HUB, socBefore, socAfter));
                cab.currentLocation = hub.getLocation();
                cab.availableTime = dhEnd;
                cab.currentSoc = socAfter;
            }

            // 2. Charging Session
            Instant chargeStart = slot.start().isAfter(cab.availableTime) ? slot.start() : cab.availableTime;
            Instant chargeEnd = chargeStart.plus(opt.chargeDur());
            double socBefore = cab.currentSoc;
            double targetSoc = ChargingCurve.chargeForDuration(socBefore, opt.chargeDur(), batteryKWh, slot.powerKW());
            double energyAdded = ChargingCurve.energyAddedKWh(socBefore, targetSoc, batteryKWh);

            ChargeEvent chargeEvent = new ChargeEvent(
                    hub.getId(), slot.chargerId(), chargeStart, chargeEnd, hub.getLocation(),
                    socBefore, targetSoc, opt.chargeCost(), energyAdded, socBefore, targetSoc
            );
            cab.events.add(chargeEvent);
            chargerTimeline.book(new ChargerTimeline.Reservation(
                    hub.getId(), slot.chargerId(), cab.vehicle.getId(), chargeStart, chargeEnd
            ));

            cab.currentLocation = hub.getLocation();
            cab.availableTime = chargeEnd;
            cab.currentSoc = targetSoc;

            // 3. Deadhead from hub to trip origin
            if (opt.dhHubToOriginDist() > PlanValidator.LOCATION_TOLERANCE_KM) {
                Instant dhEnd = trip.getStartTime();
                Instant dhStart = dhEnd.minus(opt.dhHubToOriginDur());
                if (dhStart.isBefore(cab.availableTime)) {
                    dhStart = cab.availableTime;
                }
                double eDh = (opt.dhHubToOriginDist() * cab.vehicle.getConsumptionKWhPerKm());
                double dhSocBefore = cab.currentSoc;
                double dhSocAfter = Math.max(0.0, dhSocBefore - (eDh / batteryKWh) * 100.0);

                cab.events.add(new DeadheadEvent(dhStart, dhEnd, hub.getLocation(), trip.getStartLoc(),
                        opt.dhHubToOriginDist(), eDh, DeadheadPurpose.TO_TRIP_ORIGIN, dhSocBefore, dhSocAfter));
                cab.currentLocation = trip.getStartLoc();
                cab.availableTime = dhEnd;
                cab.currentSoc = dhSocAfter;
            }
        } else {
            // Direct deadhead to trip origin (if needed)
            if (opt.dhToHubDist() > PlanValidator.LOCATION_TOLERANCE_KM) {
                Instant dhEnd = trip.getStartTime();
                Instant dhStart = dhEnd.minus(opt.dhToHubDur());
                if (dhStart.isBefore(cab.availableTime)) {
                    dhStart = cab.availableTime;
                }
                double eDh = (opt.dhToHubDist() * cab.vehicle.getConsumptionKWhPerKm());
                double dhSocBefore = cab.currentSoc;
                double dhSocAfter = Math.max(0.0, dhSocBefore - (eDh / batteryKWh) * 100.0);

                cab.events.add(new DeadheadEvent(dhStart, dhEnd, cab.currentLocation, trip.getStartLoc(),
                        opt.dhToHubDist(), eDh, DeadheadPurpose.TO_TRIP_ORIGIN, dhSocBefore, dhSocAfter));
                cab.currentLocation = trip.getStartLoc();
                cab.availableTime = dhEnd;
                cab.currentSoc = dhSocAfter;
            }
        }

        // 4. Trip Event
        double tripEnergy = (trip.getDistanceKm() * cab.vehicle.getConsumptionKWhPerKm());
        double tripDurationHours = trip.getDistanceKm() / 25.0; // base speed
        Instant tripStart = trip.getStartTime();
        Instant tripEnd = tripStart.plusSeconds(Math.max(60, Math.round(tripDurationHours * 3600.0)));
        if (tripEnd.isAfter(trip.getLatestFinish())) {
            tripEnd = trip.getLatestFinish();
        }

        double tripSocBefore = cab.currentSoc;
        double tripSocAfter = Math.max(0.0, tripSocBefore - (tripEnergy / batteryKWh) * 100.0);

        cab.events.add(new TripEvent(
                trip.getId(), tripStart, tripEnd, trip.getStartLoc(), trip.getEndLoc(),
                trip.getDistanceKm(), tripEnergy, tripSocBefore, tripSocAfter
        ));

        cab.currentLocation = trip.getEndLoc();
        cab.availableTime = tripEnd;
        cab.currentSoc = tripSocAfter;
    }

    private Plan runLocalSearch(Plan basePlan, SolverInput input, ChargerTimeline timeline, long deadlineMs) {
        Plan bestPlan = basePlan;
        double bestCost = basePlan.getCost();
        int bestUnassigned = basePlan.getUnassigned().size();

        // Charge Shifting Optimization:
        // Try shifting charging sessions into off-peak windows where slack exists
        Map<String, List<Event>> schedules = new HashMap<>();
        for (Map.Entry<String, List<Event>> entry : bestPlan.getCabSchedules().entrySet()) {
            schedules.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }

        boolean improved = false;
        for (Map.Entry<String, List<Event>> entry : schedules.entrySet()) {
            if (System.currentTimeMillis() >= deadlineMs) break;

            List<Event> events = entry.getValue();
            for (int i = 0; i < events.size(); i++) {
                if (events.get(i) instanceof ChargeEvent ce) {
                    if (input.tariff().isPeak(ce.getStartTime())) {
                        // Check if we can shift earlier into off-peak
                        Instant prevEnd = (i > 0) ? events.get(i - 1).getEndTime() : ce.getStartTime().minus(Duration.ofHours(2));
                        Duration chargeDur = Duration.between(ce.getStartTime(), ce.getEndTime());
                        Instant candidateStart = prevEnd;
                        Instant candidateEnd = candidateStart.plus(chargeDur);

                        if (!candidateStart.isAfter(ce.getStartTime()) && !input.tariff().isPeak(candidateStart)) {
                            if (timeline.isStallAvailable(ce.getChargerId(), candidateStart, candidateEnd)) {
                                timeline.cancel(ce.getChargerId(), ce.getStartTime(), ce.getEndTime());
                                timeline.book(new ChargerTimeline.Reservation(ce.getHubId(), ce.getChargerId(), entry.getKey(), candidateStart, candidateEnd));

                                double newCost = input.tariff().calculateCost(candidateStart, candidateEnd, ce.getEnergyAddedKWh());
                                ChargeEvent shifted = new ChargeEvent(
                                        ce.getHubId(), ce.getChargerId(), candidateStart, candidateEnd,
                                        ce.getStartLocation(), ce.getSocFrom(), ce.getSocTo(),
                                        newCost, ce.getEnergyAddedKWh(), ce.getSocBefore(), ce.getSocAfter()
                                );
                                events.set(i, shifted);
                                improved = true;
                            }
                        }
                    }
                }
            }
        }

        if (improved) {
            double totalCost = calculateTotalCost(schedules);
            Plan shiftedPlan = new Plan(
                    basePlan.getPlanId(), basePlan.getVersion() + 1,
                    schedules, basePlan.getUnassigned(),
                    totalCost, basePlan.getLowerBound(), new HashMap<>(basePlan.getStats())
            );
            ValidationReport vr = validator.validate(shiftedPlan, input.vehicles(), input.hubs(), input.trips());
            if (vr.valid()) {
                bestPlan = shiftedPlan;
            }
        }

        return bestPlan;
    }

    private Plan buildPlanFromCabStates(String planId, long version,
                                        List<CabState> cabStates,
                                        List<UnassignedTrip> unassignedTrips,
                                        LowerBoundCalculator.LowerBoundResult lb,
                                        Tariff tariff) {
        Map<String, List<Event>> cabSchedules = new HashMap<>();
        double totalCost = 0.0;

        for (CabState cab : cabStates) {
            cabSchedules.put(cab.vehicle.getId(), new ArrayList<>(cab.events));
            for (Event e : cab.events) {
                if (e instanceof ChargeEvent ce) {
                    totalCost += ce.getCost();
                }
            }
        }

        Plan plan = new Plan(planId, version, cabSchedules, unassignedTrips, totalCost, lb.costLB(), new HashMap<>());
        plan.putStat("lowerBoundCost", lb.costLB());
        plan.putStat("lowerBoundUnassigned", lb.unassignedLB());
        plan.putStat("optimalityGap", plan.getOptimalityGap());
        return plan;
    }

    private double calculateTotalCost(Map<String, List<Event>> schedules) {
        double cost = 0.0;
        for (List<Event> events : schedules.values()) {
            for (Event e : events) {
                if (e instanceof ChargeEvent ce) {
                    cost += ce.getCost();
                }
            }
        }
        return cost;
    }

    private Hub findNearestHub(GeoLocation loc, List<Hub> hubs) {
        Hub nearest = null;
        double minDist = Double.MAX_VALUE;
        for (Hub h : hubs) {
            double d = loc.drivingDistanceKm(h.getLocation());
            if (d < minDist) {
                minDist = d;
                nearest = h;
            }
        }
        return nearest;
    }

    private static class CabState {
        final Vehicle vehicle;
        GeoLocation currentLocation;
        Instant availableTime;
        double currentSoc;
        final List<Event> events = new ArrayList<>();

        CabState(Vehicle vehicle) {
            this.vehicle = vehicle;
            this.currentLocation = vehicle.getLocation();
            this.availableTime = Instant.parse("2026-10-31T06:00:00Z"); // shift start
            this.currentSoc = vehicle.getSocPct();
        }
    }

    private record CandidateOption(
            CabState cab,
            Trip trip,
            boolean requiresCharging,
            Hub hub,
            ChargerTimeline.AvailableSlot slot,
            Duration chargeDur,
            double dhToHubDist,
            Duration dhToHubDur,
            double dhHubToOriginDist,
            Duration dhHubToOriginDur,
            double chargeCost,
            double score
    ) {}

    private record CandidateEvaluation(boolean isFeasible, CandidateOption option, UnassignedReason failureReason) {
        static CandidateEvaluation success(CandidateOption option) {
            return new CandidateEvaluation(true, option, null);
        }
        static CandidateEvaluation failed(UnassignedReason reason) {
            return new CandidateEvaluation(false, null, reason);
        }
    }
}
