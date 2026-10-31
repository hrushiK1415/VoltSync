package com.voltsync.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record VehicleDto(
        @NotBlank String id,
        @NotBlank String tenantId,
        @Positive double batteryKWh,
        double socPct,
        @Positive double consumptionKWhPerKm,
        double lat,
        double lng,
        String hubId
) {}
