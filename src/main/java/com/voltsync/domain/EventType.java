package com.voltsync.domain;

/**
 * Discriminator for timeline event types.
 */
public enum EventType {
    TRIP,
    DEADHEAD,
    CHARGE
}
