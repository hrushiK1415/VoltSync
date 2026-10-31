package com.voltsync.engine.strategy;

import com.voltsync.domain.ReplanResult;

/**
 * Strategy interface for incremental live re-planning events.
 */
public interface ReplanStrategy {
    ReplanResult execute(ReplanContext context);
}
