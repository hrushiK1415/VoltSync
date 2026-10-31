package com.voltsync.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class GeoLocationTest {

    @Test
    @DisplayName("Haversine distance between London and Paris should be ~343 km")
    void haversineDistanceKnownCities() {
        // London: 51.5074 N, 0.1278 W
        GeoLocation london = new GeoLocation(51.5074, -0.1278);
        // Paris: 48.8566 N, 2.3522 E
        GeoLocation paris = new GeoLocation(48.8566, 2.3522);

        double distKm = london.haversineDistanceKm(paris);
        assertThat(distKm).isCloseTo(343.5, within(2.0));
    }

    @Test
    @DisplayName("Distance to self must be 0")
    void distanceToSelfIsZero() {
        GeoLocation loc = new GeoLocation(12.9716, 77.5946); // Bangalore
        assertThat(loc.haversineDistanceKm(loc)).isZero();
        assertThat(loc.drivingDistanceKm(loc)).isZero();
    }

    @Test
    @DisplayName("Road driving distance applies 1.3 winding factor")
    void drivingDistanceAppliesRoadFactor() {
        GeoLocation p1 = new GeoLocation(0.0, 0.0);
        GeoLocation p2 = new GeoLocation(1.0, 0.0);

        double crowFly = p1.haversineDistanceKm(p2);
        double roadDistance = p1.drivingDistanceKm(p2);

        assertThat(roadDistance).isEqualTo(crowFly * 1.3);
    }

    @Test
    @DisplayName("Latitude and Longitude bounds must be validated")
    void invalidCoordinatesThrowException() {
        assertThatThrownBy(() -> new GeoLocation(91.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GeoLocation(-91.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GeoLocation(0.0, 181.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GeoLocation(0.0, -181.0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
