package com.voltsync.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltsync.controller.dto.*;
import com.voltsync.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReplanIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private VehicleRepository vehicleRepository;
    @Autowired
    private HubRepository hubRepository;
    @Autowired
    private ChargerRepository chargerRepository;
    @Autowired
    private TripRepository tripRepository;
    @Autowired
    private PlanRepository planRepository;
    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @BeforeEach
    void cleanDatabase() {
        processedEventRepository.deleteAll();
        planRepository.deleteAll();
        tripRepository.deleteAll();
        chargerRepository.deleteAll();
        hubRepository.deleteAll();
        vehicleRepository.deleteAll();
    }

    @Test
    @DisplayName("Battery drift live re-plan, idempotency (duplicate 200), and out-of-order rejection (409)")
    void batteryDriftIdempotencyAndOrdering() throws Exception {
        // Setup fleet and solve
        ChargerDto ch1 = new ChargerDto("CH10", 50.0, "UP");
        ChargerDto ch2 = new ChargerDto("CH20", 50.0, "UP");
        HubDto hub = new HubDto("HUB10", "TENANT2", 12.9500, 77.6000, List.of(ch1, ch2));
        mockMvc.perform(post("/fleet/hubs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(List.of(hub)))).andExpect(status().isOk());

        VehicleDto v1 = new VehicleDto("CAB10", "TENANT2", 60.0, 90.0, 0.18, 12.9716, 77.5946, "HUB10");
        mockMvc.perform(post("/fleet/vehicles")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(List.of(v1)))).andExpect(status().isOk());

        Instant t0800 = Instant.parse("2026-10-31T08:00:00Z");
        TripDto trip = new TripDto("TRIP10", "TENANT2", t0800, 12.9716, 77.5946, 12.9352, 77.6245, 5.0, t0800.plusSeconds(3600));
        mockMvc.perform(post("/trips")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(List.of(trip)))).andExpect(status().isOk());

        MvcResult solveResult = mockMvc.perform(post("/plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SolveRequest("TENANT2", 5))))
                .andExpect(status().isOk())
                .andReturn();

        SolveResponse solveResponse = objectMapper.readValue(solveResult.getResponse().getContentAsString(), SolveResponse.class);
        String planId = solveResponse.planId();

        Instant t0830 = Instant.parse("2026-10-31T08:30:00Z");
        BatteryDriftRequest req1 = new BatteryDriftRequest("EVT-001", "CAB10", 75.0, t0830);

        mockMvc.perform(post("/plans/" + planId + "/events/battery-drift")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planId", is(planId)))
                .andExpect(jsonPath("$.replanDurationMs", greaterThanOrEqualTo(0)));

        // Idempotency guarantee: resending identical eventId returns HTTP 200 with cached diff
        mockMvc.perform(post("/plans/" + planId + "/events/battery-drift")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planId", is(planId)));

        // Ordering guarantee: rejecting events timestamped prior to last recorded telemetry (HTTP 409)
        Instant t0815 = Instant.parse("2026-10-31T08:15:00Z");
        BatteryDriftRequest outOfOrderReq = new BatteryDriftRequest("EVT-002", "CAB10", 80.0, t0815);

        mockMvc.perform(post("/plans/" + planId + "/events/battery-drift")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(outOfOrderReq)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("OUT_OF_ORDER_EVENT")))
                .andExpect(jsonPath("$.message", containsString("earlier than last applied")));
    }

    @Test
    @DisplayName("Charger failure live re-plan and idempotency")
    void chargerFailureIdempotency() throws Exception {
        ChargerDto ch1 = new ChargerDto("CH_FAIL_1", 50.0, "UP");
        ChargerDto ch2 = new ChargerDto("CH_FAIL_2", 50.0, "UP");
        HubDto hub = new HubDto("HUB_FAIL", "TENANT3", 12.9500, 77.6000, List.of(ch1, ch2));
        mockMvc.perform(post("/fleet/hubs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(List.of(hub)))).andExpect(status().isOk());

        VehicleDto v1 = new VehicleDto("CAB_FAIL", "TENANT3", 60.0, 90.0, 0.18, 12.9716, 77.5946, "HUB_FAIL");
        mockMvc.perform(post("/fleet/vehicles")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(List.of(v1)))).andExpect(status().isOk());

        Instant t0800 = Instant.parse("2026-10-31T08:00:00Z");
        TripDto trip = new TripDto("TRIP_FAIL", "TENANT3", t0800, 12.9716, 77.5946, 12.9352, 77.6245, 5.0, t0800.plusSeconds(3600));
        mockMvc.perform(post("/trips")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(List.of(trip)))).andExpect(status().isOk());

        MvcResult solveResult = mockMvc.perform(post("/plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SolveRequest("TENANT3", 5))))
                .andExpect(status().isOk())
                .andReturn();

        SolveResponse solveResponse = objectMapper.readValue(solveResult.getResponse().getContentAsString(), SolveResponse.class);
        String planId = solveResponse.planId();

        // Charger breaks at 08:10
        ChargerFailureRequest req = new ChargerFailureRequest("EVT-CF-1", "CH_FAIL_1", Instant.parse("2026-10-31T08:10:00Z"));

        // First call: 200 OK
        mockMvc.perform(post("/plans/" + planId + "/events/charger-failure")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planId", is(planId)));

        // Duplicate call: 200 OK (idempotency deduplication)
        mockMvc.perform(post("/plans/" + planId + "/events/charger-failure")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planId", is(planId)));
    }
}
