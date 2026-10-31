package com.voltsync.controller.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record HubDto(
        @NotBlank String id,
        @NotBlank String tenantId,
        double lat,
        double lng,
        List<ChargerDto> chargers
) {}
