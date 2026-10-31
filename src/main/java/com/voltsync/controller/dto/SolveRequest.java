package com.voltsync.controller.dto;

public record SolveRequest(
        String tenantId,
        Integer timeBudgetSeconds
) {}
