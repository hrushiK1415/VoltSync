package com.voltsync.controller.dto;

public record SolveResponse(
        String planId,
        long version,
        double cost,
        double lowerBound,
        double optimalityGap,
        int unassignedCount,
        long solveTimeMs
) {}
