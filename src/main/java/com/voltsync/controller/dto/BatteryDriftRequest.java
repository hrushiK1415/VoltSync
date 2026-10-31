package com.voltsync.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record BatteryDriftRequest(
        @NotBlank String eventId,
        @NotBlank String vehicleId,
        double soc,
        @NotNull Instant at
) {}
