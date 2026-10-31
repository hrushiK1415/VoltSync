package com.voltsync.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Charging Hub entity containing physical location and a fleet of chargers.
 */
public class Hub {
    private final String id;
    private final String tenantId;
    private final GeoLocation location;
    private final List<Charger> chargers;

    public Hub(String id, String tenantId, GeoLocation location, List<Charger> chargers) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
        this.location = Objects.requireNonNull(location, "location must not be null");
        this.chargers = chargers != null ? new ArrayList<>(chargers) : new ArrayList<>();
    }

    public String getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public GeoLocation getLocation() {
        return location;
    }

    public List<Charger> getChargers() {
        return Collections.unmodifiableList(chargers);
    }

    public void addCharger(Charger charger) {
        chargers.add(Objects.requireNonNull(charger));
    }

    public int totalChargersCount() {
        return chargers.size();
    }

    public long activeChargersCount() {
        return chargers.stream().filter(Charger::isUp).count();
    }

    public List<Charger> getActiveChargers() {
        return chargers.stream().filter(Charger::isUp).toList();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Hub hub = (Hub) o;
        return Objects.equals(id, hub.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Hub{" +
                "id='" + id + '\'' +
                ", tenantId='" + tenantId + '\'' +
                ", location=" + location +
                ", chargers=" + chargers.size() +
                '}';
    }
}
