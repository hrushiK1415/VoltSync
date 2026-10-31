package com.voltsync.domain;

import java.util.*;

/**
 * Complete fleet assignment and charging schedule output.
 */
public class Plan {
    private final String planId;
    private long version;
    private final Map<String, List<Event>> cabSchedules;
    private final List<UnassignedTrip> unassigned;
    private double cost;
    private double lowerBound;
    private final Map<String, Object> stats;

    public Plan(String planId, long version,
                Map<String, List<Event>> cabSchedules,
                List<UnassignedTrip> unassigned,
                double cost, double lowerBound,
                Map<String, Object> stats) {
        this.planId = Objects.requireNonNull(planId, "planId must not be null");
        this.version = version;
        this.cabSchedules = cabSchedules != null ? new HashMap<>(cabSchedules) : new HashMap<>();
        this.unassigned = unassigned != null ? new ArrayList<>(unassigned) : new ArrayList<>();
        this.cost = cost;
        this.lowerBound = lowerBound;
        this.stats = stats != null ? new HashMap<>(stats) : new HashMap<>();
    }

    public String getPlanId() {
        return planId;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public Map<String, List<Event>> getCabSchedules() {
        return Collections.unmodifiableMap(cabSchedules);
    }

    public List<Event> getScheduleForCab(String vehicleId) {
        return cabSchedules.getOrDefault(vehicleId, Collections.emptyList());
    }

    public void setScheduleForCab(String vehicleId, List<Event> events) {
        cabSchedules.put(vehicleId, new ArrayList<>(events));
    }

    public List<UnassignedTrip> getUnassigned() {
        return Collections.unmodifiableList(unassigned);
    }

    public void addUnassignedTrip(UnassignedTrip unassignedTrip) {
        this.unassigned.add(Objects.requireNonNull(unassignedTrip));
    }

    public double getCost() {
        return cost;
    }

    public void setCost(double cost) {
        this.cost = cost;
    }

    public double getLowerBound() {
        return lowerBound;
    }

    public void setLowerBound(double lowerBound) {
        this.lowerBound = lowerBound;
    }

    public Map<String, Object> getStats() {
        return Collections.unmodifiableMap(stats);
    }

    public void putStat(String key, Object value) {
        this.stats.put(key, value);
    }

    public double getOptimalityGap() {
        if (lowerBound <= 0.0) {
            return 0.0;
        }
        return (cost - lowerBound) / lowerBound;
    }
}
