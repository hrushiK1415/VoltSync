package com.voltsync.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record TripDto(
        @NotBlank String id,
        @NotBlank String tenantId,
        @NotNull Instant startTime,
        double startLat,
        double startLng,
        double endLat,
        double endLng,
        double distanceKm,
        @NotNull Instant latestFinish
) {}
