package com.voltsync.engine;

import com.voltsync.domain.*;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Computes theoretical lower bounds for unassigned trips and total charging cost.
 * - Unassigned LB: count of trips that are individually impossible to serve by any cab.
 * - Cost LB: total trip energy * off-peak electricity rate (all energy charged off-peak with zero deadhead).
 */
public class LowerBoundCalculator {

    public record LowerBoundResult(int unassignedLB, double costLB) {
        public double computeGap(double actualCost) {
            if (costLB <= 0.0) {
                return 0.0;
            }
            return Math.max(0.0, (actualCost - costLB) / costLB);
        }
    }

    public LowerBoundResult calculate(List<Vehicle> vehicles,
                                      List<Hub> hubs,
                                      List<Trip> trips,
                                      Tariff tariff,
                                      ConsumptionModel model) {
        Objects.requireNonNull(vehicles, "vehicles must not be null");
        Objects.requireNonNull(trips, "trips must not be null");
        Objects.requireNonNull(tariff, "tariff must not be null");
        Objects.requireNonNull(model, "model must not be null");

        int unassignedLB = 0;
        double totalTripEnergyKWh = 0.0;

        double maxBatteryKWh = vehicles.stream()
                .mapToDouble(Vehicle::getBatteryKWh)
                .max()
                .orElse(60.0);

        for (Trip trip : trips) {
            // Check individual feasibility
            Duration tripDuration = model.travelDuration(trip.getDistanceKm(), trip.getStartTime());
            Duration windowDuration = Duration.between(trip.getStartTime(), trip.getLatestFinish());

            // 1. Time infeasibility: trip alone takes longer than available window
            if (tripDuration.compareTo(windowDuration) > 0) {
                unassignedLB++;
                continue;
            }

            // 2. Energy infeasibility: trip energy alone exceeds max allowable usable battery (85% = 100% - 15% floor)
            double tripEnergy = model.calculateEnergy(trip.getDistanceKm(), trip.getStartTime());
            double maxUsableEnergy = (100.0 - PlanValidator.BATTERY_FLOOR_PERCENT) / 100.0 * maxBatteryKWh;

            if (tripEnergy > maxUsableEnergy) {
                unassignedLB++;
                continue;
            }

            totalTripEnergyKWh += tripEnergy;
        }

        double costLB = totalTripEnergyKWh * tariff.getOffPeakRatePerKWh();
        return new LowerBoundResult(unassignedLB, costLB);
    }
}
