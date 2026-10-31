package com.voltsync.engine;

import com.voltsync.domain.*;
import com.voltsync.engine.strategy.*;

import java.time.Instant;
import java.util.List;

/**
 * High-level coordinator for live incremental re-planning operations.
 * Executes specific event strategies and validates updated plans under a 2-second SLA.
 */
public class IncrementalReplanner {

    public ReplanResult handleBatteryDrift(Plan currentPlan,
                                          String vehicleId,
                                          double actualSoc,
                                          Instant atTime,
                                          List<Vehicle> vehicles,
                                          List<Hub> hubs,
                                          List<Trip> trips,
                                          Tariff tariff,
                                          ConsumptionModel model,
                                          ChargerTimeline chargerTimeline) {
        ReplanContext context = new ReplanContext(
                currentPlan, vehicles, hubs, trips, tariff, model, chargerTimeline
        );
        BatteryDriftStrategy strategy = new BatteryDriftStrategy(vehicleId, actualSoc, atTime);
        return strategy.execute(context);
    }

    public ReplanResult handleChargerFailure(Plan currentPlan,
                                            String brokenChargerId,
                                            Instant atTime,
                                            List<Vehicle> vehicles,
                                            List<Hub> hubs,
                                            List<Trip> trips,
                                            Tariff tariff,
                                            ConsumptionModel model,
                                            ChargerTimeline chargerTimeline) {
        ReplanContext context = new ReplanContext(
                currentPlan, vehicles, hubs, trips, tariff, model, chargerTimeline
        );
        ChargerFailureStrategy strategy = new ChargerFailureStrategy(brokenChargerId, atTime);
        return strategy.execute(context);
    }
}
