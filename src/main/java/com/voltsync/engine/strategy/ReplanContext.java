package com.voltsync.engine.strategy;

import com.voltsync.domain.*;
import com.voltsync.engine.ChargerTimeline;

import java.util.List;
import java.util.Objects;

/**
 * Context payload containing the current state for an incremental re-plan.
 */
public record ReplanContext(
        Plan currentPlan,
        List<Vehicle> vehicles,
        List<Hub> hubs,
        List<Trip> trips,
        Tariff tariff,
        ConsumptionModel model,
        ChargerTimeline chargerTimeline
) {
    public ReplanContext {
        Objects.requireNonNull(currentPlan, "currentPlan must not be null");
        Objects.requireNonNull(vehicles, "vehicles must not be null");
        Objects.requireNonNull(hubs, "hubs must not be null");
        Objects.requireNonNull(trips, "trips must not be null");
        Objects.requireNonNull(tariff, "tariff must not be null");
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(chargerTimeline, "chargerTimeline must not be null");
    }
}
