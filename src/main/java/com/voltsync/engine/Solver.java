package com.voltsync.engine;

import com.voltsync.domain.*;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Interface for fleet assignment and charging schedule solvers.
 */
public interface Solver {

    record SolverInput(
            String planId,
            List<Vehicle> vehicles,
            List<Hub> hubs,
            List<Trip> trips,
            Tariff tariff,
            ConsumptionModel consumptionModel,
            Duration timeBudget
    ) {
        public SolverInput {
            Objects.requireNonNull(planId, "planId must not be null");
            Objects.requireNonNull(vehicles, "vehicles must not be null");
            Objects.requireNonNull(hubs, "hubs must not be null");
            Objects.requireNonNull(trips, "trips must not be null");
            Objects.requireNonNull(tariff, "tariff must not be null");
            Objects.requireNonNull(consumptionModel, "consumptionModel must not be null");
            if (timeBudget == null || timeBudget.isNegative() || timeBudget.isZero()) {
                timeBudget = Duration.ofSeconds(15);
            }
        }
    }

    Plan solve(SolverInput input);
}
