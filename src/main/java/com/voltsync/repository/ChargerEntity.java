package com.voltsync.repository;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "chargers")
public class ChargerEntity {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "hub_id", nullable = false)
    private String hubId;

    @Column(name = "power_kw", nullable = false)
    private double powerKW;

    @Column(name = "status", nullable = false)
    private String status;

    public ChargerEntity() {
    }

    public ChargerEntity(String id, String hubId, double powerKW, String status) {
        this.id = id;
        this.hubId = hubId;
        this.powerKW = powerKW;
        this.status = status;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getHubId() {
        return hubId;
    }

    public void setHubId(String hubId) {
        this.hubId = hubId;
    }

    public double getPowerKW() {
        return powerKW;
    }

    public void setPowerKW(double powerKW) {
        this.powerKW = powerKW;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
