package com.voltsync.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record ChargerDto(
        @NotBlank String id,
        @Positive double powerKW,
        String status
) {}
