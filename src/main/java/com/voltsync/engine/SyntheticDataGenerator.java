package com.voltsync.engine;

import com.voltsync.domain.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Deterministic synthetic benchmark scenario generator for 300 cabs, 40 chargers, and 1,500 trips.
 */
public class SyntheticDataGenerator {

    public record Scenario(
            List<Vehicle> vehicles,
            List<Hub> hubs,
            List<Trip> trips,
            Tariff tariff,
            ConsumptionModel model
    ) {}

    public Scenario generateBenchmarkScenario(long seed, int numCabs, int numChargers, int numTrips) {
        Random rng = new Random(seed);

        GeoLocation cityCenter = new GeoLocation(12.9716, 77.5946);
        double cityRadiusKm = 18.0;

        int chargersPerHub = 5;
        int numHubs = Math.max(1, (int) Math.ceil((double) numChargers / chargersPerHub));

        List<Hub> hubs = new ArrayList<>();
        int chargersCreated = 0;

        for (int h = 1; h <= numHubs; h++) {
            GeoLocation hubLoc = randomLocation(rng, cityCenter, cityRadiusKm * 0.7);
            List<Charger> chargers = new ArrayList<>();
            int countForThisHub = Math.min(chargersPerHub, numChargers - chargersCreated);

            for (int c = 1; c <= countForThisHub; c++) {
                String chargerId = "HUB" + h + "-CH" + c;
                double powerKW = (c % 2 == 0) ? 100.0 : 50.0;
                chargers.add(new Charger(chargerId, "HUB" + h, powerKW, ChargerStatus.UP));
                chargersCreated++;
            }
            hubs.add(new Hub("HUB" + h, "TENANT1", hubLoc, chargers));
        }

        List<Vehicle> vehicles = new ArrayList<>();
        for (int v = 1; v <= numCabs; v++) {
            String cabId = "CAB-" + String.format("%03d", v);
            GeoLocation loc = randomLocation(rng, cityCenter, cityRadiusKm);
            double batteryKWh = (v % 3 == 0) ? 75.0 : 60.0;
            // 25% to 45% initial SoC forces mid-shift charging to test queueing and off-peak tariffs
            double initialSoc = 25.0 + rng.nextDouble() * 20.0;
            double consumption = 0.18;
            Hub homeHub = hubs.get(rng.nextInt(hubs.size()));

            vehicles.add(new Vehicle(cabId, "TENANT1", batteryKWh, initialSoc, consumption, loc, homeHub.getId()));
        }

        Instant shiftStart = Instant.parse("2026-10-31T06:00:00Z");
        long shiftSeconds = Duration.ofHours(16).toSeconds();

        ConsumptionModel model = ConsumptionModel.defaultModel();
        List<Trip> trips = new ArrayList<>();

        for (int t = 1; t <= numTrips; t++) {
            String tripId = "TRIP-" + String.format("%04d", t);

            // Bimodal distribution reflecting morning (08:00-11:00) and evening (17:00-20:00) commuter demand
            double r = rng.nextDouble();
            long offsetSeconds;
            if (r < 0.40) {
                offsetSeconds = (2 * 3600) + Math.round(rng.nextDouble() * 3 * 3600);
            } else if (r < 0.80) {
                offsetSeconds = (11 * 3600) + Math.round(rng.nextDouble() * 3 * 3600);
            } else {
                offsetSeconds = Math.round(rng.nextDouble() * (shiftSeconds - 3600));
            }

            Instant tripStartTime = shiftStart.plusSeconds(offsetSeconds);

            GeoLocation startLoc = randomLocation(rng, cityCenter, cityRadiusKm);
            GeoLocation endLoc = randomLocation(rng, cityCenter, cityRadiusKm);
            double roadDist = startLoc.drivingDistanceKm(endLoc);

            Duration travelDuration = model.travelDuration(roadDist, tripStartTime);
            long bufferSeconds = 900 + rng.nextInt(900);
            Instant latestFinish = tripStartTime.plus(travelDuration).plusSeconds(bufferSeconds);

            trips.add(new Trip(tripId, "TENANT1", tripStartTime, startLoc, endLoc, roadDist, latestFinish));
        }

        Tariff tariff = Tariff.defaultTariff();
        return new Scenario(vehicles, hubs, trips, tariff, model);
    }

    private GeoLocation randomLocation(Random rng, GeoLocation center, double maxRadiusKm) {
        // Uniform circular distribution
        double u = rng.nextDouble();
        double radius = maxRadiusKm * Math.sqrt(u);
        double theta = rng.nextDouble() * 2.0 * Math.PI;

        double deltaLat = (radius * Math.cos(theta)) / 111.0;
        double deltaLng = (radius * Math.sin(theta)) / (111.0 * Math.cos(Math.toRadians(center.lat())));

        return new GeoLocation(center.lat() + deltaLat, center.lng() + deltaLng);
    }
}
