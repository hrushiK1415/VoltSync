package com.voltsync.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltsync.common.AppException;
import com.voltsync.common.ErrorCode;
import com.voltsync.controller.dto.BatteryDriftRequest;
import com.voltsync.controller.dto.ChargerFailureRequest;
import com.voltsync.domain.*;
import com.voltsync.engine.ChargerTimeline;
import com.voltsync.engine.IncrementalReplanner;
import com.voltsync.repository.ProcessedEventEntity;
import com.voltsync.repository.ProcessedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class ReplanService {

    private static final Logger log = LoggerFactory.getLogger(ReplanService.class);

    private final PlanningService planningService;
    private final FleetService fleetService;
    private final ProcessedEventRepository processedEventRepository;
    private final ObjectMapper objectMapper;
    private final IncrementalReplanner replanner = new IncrementalReplanner();

    public ReplanService(PlanningService planningService,
                         FleetService fleetService,
                         ProcessedEventRepository processedEventRepository,
                         ObjectMapper objectMapper) {
        this.planningService = planningService;
        this.fleetService = fleetService;
        this.processedEventRepository = processedEventRepository;
        this.objectMapper = objectMapper;
    }

    public ReplanDiff handleBatteryDrift(String planId, BatteryDriftRequest req) {
        log.info("Processing battery drift for planId={}, vehicleId={}, eventId={}", planId, req.vehicleId(), req.eventId());

        // Idempotency: return cached response diff for duplicate eventId (HTTP 200)
        Optional<ProcessedEventEntity> existing = processedEventRepository.findById(req.eventId());
        if (existing.isPresent()) {
            log.info("Duplicate eventId={} detected. Returning cached result (idempotency 200).", req.eventId());
            try {
                return objectMapper.readValue(existing.get().getResultDiffJson(), ReplanDiff.class);
            } catch (JsonProcessingException e) {
                log.error("Failed to parse cached diff JSON for eventId={}", req.eventId(), e);
            }
        }

        // Out-of-order check: reject events arriving earlier than last applied telemetry for this vehicle (HTTP 409)
        List<ProcessedEventEntity> vehicleHistory = processedEventRepository
                .findByVehicleIdOrderByTimestampDesc(req.vehicleId());
        if (!vehicleHistory.isEmpty() && req.at().isBefore(vehicleHistory.get(0).getEventTimestamp())) {
            throw new AppException(
                    ErrorCode.OUT_OF_ORDER_EVENT,
                    "Rejected out-of-order event for vehicle " + req.vehicleId() +
                            ": event timestamp " + req.at() + " is earlier than last applied " +
                            vehicleHistory.get(0).getEventTimestamp()
            );
        }

        Plan plan = planningService.getPlan(planId);
        List<Vehicle> vehicles = fleetService.getAllVehiclesDomain();
        List<Hub> hubs = fleetService.getAllHubsDomain();
        List<Trip> trips = fleetService.getAllTripsDomain();

        // Reconstruct ChargerTimeline state from current plan
        ChargerTimeline timeline = buildTimelineFromPlan(plan);

        ReplanResult result = replanner.handleBatteryDrift(
                plan, req.vehicleId(), req.soc(), req.at(),
                vehicles, hubs, trips, Tariff.defaultTariff(), ConsumptionModel.defaultModel(), timeline
        );

        planningService.updatePlan(result.plan(), result.diff().baseVersion());

        try {
            String diffJson = objectMapper.writeValueAsString(result.diff());
            processedEventRepository.save(new ProcessedEventEntity(
                    req.eventId(), planId, req.vehicleId(), "BATTERY_DRIFT", req.at(), diffJson
            ));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize diff for eventId={}", req.eventId(), e);
        }

        return result.diff();
    }

    public ReplanDiff handleChargerFailure(String planId, ChargerFailureRequest req) {
        log.info("Processing charger failure for planId={}, chargerId={}, eventId={}", planId, req.chargerId(), req.eventId());

        // Idempotency: return cached response diff for duplicate eventId (HTTP 200)
        Optional<ProcessedEventEntity> existing = processedEventRepository.findById(req.eventId());
        if (existing.isPresent()) {
            log.info("Duplicate eventId={} detected. Returning cached result (idempotency 200).", req.eventId());
            try {
                return objectMapper.readValue(existing.get().getResultDiffJson(), ReplanDiff.class);
            } catch (JsonProcessingException e) {
                log.error("Failed to parse cached diff JSON for eventId={}", req.eventId(), e);
            }
        }

        Plan plan = planningService.getPlan(planId);
        List<Vehicle> vehicles = fleetService.getAllVehiclesDomain();
        List<Hub> hubs = fleetService.getAllHubsDomain();
        List<Trip> trips = fleetService.getAllTripsDomain();

        ChargerTimeline timeline = buildTimelineFromPlan(plan);

        ReplanResult result = replanner.handleChargerFailure(
                plan, req.chargerId(), req.at(),
                vehicles, hubs, trips, Tariff.defaultTariff(), ConsumptionModel.defaultModel(), timeline
        );

        planningService.updatePlan(result.plan(), result.diff().baseVersion());

        try {
            String diffJson = objectMapper.writeValueAsString(result.diff());
            processedEventRepository.save(new ProcessedEventEntity(
                    req.eventId(), planId, null, "CHARGER_FAILURE", req.at(), diffJson
            ));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize diff for eventId={}", req.eventId(), e);
        }

        return result.diff();
    }

    private ChargerTimeline buildTimelineFromPlan(Plan plan) {
        ChargerTimeline timeline = new ChargerTimeline();
        for (var entry : plan.getCabSchedules().entrySet()) {
            String vehicleId = entry.getKey();
            for (Event e : entry.getValue()) {
                if (e instanceof ChargeEvent ce) {
                    try {
                        timeline.book(new ChargerTimeline.Reservation(
                                ce.getHubId(), ce.getChargerId(), vehicleId, ce.getStartTime(), ce.getEndTime()
                        ));
                    } catch (IllegalStateException ignored) {
                        // Tolerate existing bookings
                    }
                }
            }
        }
        return timeline;
    }
}
