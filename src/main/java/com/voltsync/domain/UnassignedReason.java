package com.voltsync.domain;

/**
 * Explanatory reason why a trip could not be assigned to an EV cab.
 */
public enum UnassignedReason {
    NO_CAB_IN_TIME,
    INSUFFICIENT_CHARGE,
    CHARGER_CONGESTION,
    TIME_WINDOW_INFEASIBLE
}
