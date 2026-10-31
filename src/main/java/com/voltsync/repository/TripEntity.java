package com.voltsync.repository;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "trips")
public class TripEntity {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    @Column(name = "start_lat", nullable = false)
    private double startLat;

    @Column(name = "start_lng", nullable = false)
    private double startLng;

    @Column(name = "end_lat", nullable = false)
    private double endLat;

    @Column(name = "end_lng", nullable = false)
    private double endLng;

    @Column(name = "distance_km", nullable = false)
    private double distanceKm;

    @Column(name = "latest_finish", nullable = false)
    private Instant latestFinish;

    public TripEntity() {
    }

    public TripEntity(String id, String tenantId, Instant startTime,
                      double startLat, double startLng, double endLat, double endLng,
                      double distanceKm, Instant latestFinish) {
        this.id = id;
        this.tenantId = tenantId;
        this.startTime = startTime;
        this.startLat = startLat;
        this.startLng = startLng;
        this.endLat = endLat;
        this.endLng = endLng;
        this.distanceKm = distanceKm;
        this.latestFinish = latestFinish;
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

    public Instant getStartTime() {
        return startTime;
    }

    public void setStartTime(Instant startTime) {
        this.startTime = startTime;
    }

    public double getStartLat() {
        return startLat;
    }

    public void setStartLat(double startLat) {
        this.startLat = startLat;
    }

    public double getStartLng() {
        return startLng;
    }

    public void setStartLng(double startLng) {
        this.startLng = startLng;
    }

    public double getEndLat() {
        return endLat;
    }

    public void setEndLat(double endLat) {
        this.endLat = endLat;
    }

    public double getEndLng() {
        return endLng;
    }

    public void setEndLng(double endLng) {
        this.endLng = endLng;
    }

    public double getDistanceKm() {
        return distanceKm;
    }

    public void setDistanceKm(double distanceKm) {
        this.distanceKm = distanceKm;
    }

    public Instant getLatestFinish() {
        return latestFinish;
    }

    public void setLatestFinish(Instant latestFinish) {
        this.latestFinish = latestFinish;
    }
}
