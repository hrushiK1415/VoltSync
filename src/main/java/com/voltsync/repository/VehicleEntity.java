package com.voltsync.repository;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "vehicles")
public class VehicleEntity {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "battery_kwh", nullable = false)
    private double batteryKWh;

    @Column(name = "soc_pct", nullable = false)
    private double socPct;

    @Column(name = "consumption_kwh_per_km", nullable = false)
    private double consumptionKWhPerKm;

    @Column(name = "lat", nullable = false)
    private double lat;

    @Column(name = "lng", nullable = false)
    private double lng;

    @Column(name = "hub_id")
    private String hubId;

    public VehicleEntity() {
    }

    public VehicleEntity(String id, String tenantId, double batteryKWh, double socPct,
                         double consumptionKWhPerKm, double lat, double lng, String hubId) {
        this.id = id;
        this.tenantId = tenantId;
        this.batteryKWh = batteryKWh;
        this.socPct = socPct;
        this.consumptionKWhPerKm = consumptionKWhPerKm;
        this.lat = lat;
        this.lng = lng;
        this.hubId = hubId;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public double getBatteryKWh() {
        return batteryKWh;
    }

    public void setBatteryKWh(double batteryKWh) {
        this.batteryKWh = batteryKWh;
    }

    public double getSocPct() {
        return socPct;
    }

    public void setSocPct(double socPct) {
        this.socPct = socPct;
    }

    public double getConsumptionKWhPerKm() {
        return consumptionKWhPerKm;
    }

    public void setConsumptionKWhPerKm(double consumptionKWhPerKm) {
        this.consumptionKWhPerKm = consumptionKWhPerKm;
    }

    public double getLat() {
        return lat;
    }

    public void setLat(double lat) {
        this.lat = lat;
    }

    public double getLng() {
        return lng;
    }

    public void setLng(double lng) {
        this.lng = lng;
    }

    public String getHubId() {
        return hubId;
    }

    public void setHubId(String hubId) {
        this.hubId = hubId;
    }
}
