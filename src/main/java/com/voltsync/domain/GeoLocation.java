package com.voltsync.domain;

import java.util.Objects;

/**
 * Geographic location represented by latitude and longitude coordinates.
 * Includes spherical distance calculation and road distance estimation.
 */
public record GeoLocation(double lat, double lng) {

    public static final double EARTH_RADIUS_KM = 6371.0;
    public static final double ROAD_FACTOR = 1.3;

    public GeoLocation {
        if (lat < -90.0 || lat > 90.0) {
            throw new IllegalArgumentException("Latitude must be between -90 and 90, got: " + lat);
        }
        if (lng < -180.0 || lng > 180.0) {
            throw new IllegalArgumentException("Longitude must be between -180 and 180, got: " + lng);
        }
    }

    /**
     * Calculates the great-circle (Haversine) distance between this point and another.
     */
    public double haversineDistanceKm(GeoLocation other) {
        Objects.requireNonNull(other, "other location must not be null");
        double dLat = Math.toRadians(other.lat() - this.lat());
        double dLng = Math.toRadians(other.lng() - this.lng());

        double lat1 = Math.toRadians(this.lat());
        double lat2 = Math.toRadians(other.lat());

        double a = Math.sin(dLat / 2.0) * Math.sin(dLat / 2.0)
                + Math.sin(dLng / 2.0) * Math.sin(dLng / 2.0) * Math.cos(lat1) * Math.cos(lat2);
        double c = 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));

        return EARTH_RADIUS_KM * c;
    }

    /**
     * Estimates actual road driving distance using the 1.3 winding road factor.
     */
    public double drivingDistanceKm(GeoLocation other) {
        return haversineDistanceKm(other) * ROAD_FACTOR;
    }
}
