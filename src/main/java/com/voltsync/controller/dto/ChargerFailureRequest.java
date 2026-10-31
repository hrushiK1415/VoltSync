package com.voltsync.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record ChargerFailureRequest(
        @NotBlank String eventId,
        @NotBlank String chargerId,
        @NotNull Instant at
) {}
