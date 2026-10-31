package com.voltsync.common;

/**
 * Standard system error codes for VoltSync.
 */
public enum ErrorCode {
    PLAN_NOT_FOUND("PLAN_NOT_FOUND", 404),
    VEHICLE_NOT_FOUND("VEHICLE_NOT_FOUND", 404),
    CHARGER_NOT_FOUND("CHARGER_NOT_FOUND", 404),
    HUB_NOT_FOUND("HUB_NOT_FOUND", 404),
    OUT_OF_ORDER_EVENT("OUT_OF_ORDER_EVENT", 409),
    CONCURRENT_MODIFICATION("CONCURRENT_MODIFICATION", 409),
    INVALID_INPUT("INVALID_INPUT", 400),
    SERVICE_UNAVAILABLE("SERVICE_UNAVAILABLE", 503),
    INTERNAL_ERROR("INTERNAL_ERROR", 500);

    private final String code;
    private final int httpStatus;

    ErrorCode(String code, int httpStatus) {
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public String getCode() {
        return code;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
