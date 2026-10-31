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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FleetAndPlanIntegrationTest {

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
    @DisplayName("End-to-End: Ingest fleet, solve shift, validate plan, and query per-cab timeline")
    void endToEndFleetSolveAndQuery() throws Exception {
        ChargerDto ch1 = new ChargerDto("CH1", 50.0, "UP");
        ChargerDto ch2 = new ChargerDto("CH2", 50.0, "UP");
        HubDto hub1 = new HubDto("HUB1", "TENANT1", 12.9500, 77.6000, List.of(ch1, ch2));

        mockMvc.perform(post("/fleet/hubs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of(hub1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count", is(1)));

        VehicleDto v1 = new VehicleDto("CAB1", "TENANT1", 60.0, 90.0, 0.18, 12.9716, 77.5946, "HUB1");
        VehicleDto v2 = new VehicleDto("CAB2", "TENANT1", 60.0, 85.0, 0.18, 12.9716, 77.5946, "HUB1");

        mockMvc.perform(post("/fleet/vehicles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of(v1, v2))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count", is(2)));

        Instant t0800 = Instant.parse("2026-10-31T08:00:00Z");
        Instant t0900 = Instant.parse("2026-10-31T09:00:00Z");

        TripDto trip1 = new TripDto("TRIP1", "TENANT1", t0800, 12.9716, 77.5946, 12.9352, 77.6245, 5.0, t0800.plusSeconds(2400));
        TripDto trip2 = new TripDto("TRIP2", "TENANT1", t0900, 12.9352, 77.6245, 12.9141, 77.6109, 4.0, t0900.plusSeconds(2400));

        mockMvc.perform(post("/trips")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of(trip1, trip2))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count", is(2)));

        SolveRequest solveReq = new SolveRequest("TENANT1", 5);
        MvcResult solveResult = mockMvc.perform(post("/plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(solveReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planId", notNullValue()))
                .andExpect(jsonPath("$.version", is(1)))
                .andExpect(jsonPath("$.lowerBound", notNullValue()))
                .andReturn();

        SolveResponse solveResponse = objectMapper.readValue(solveResult.getResponse().getContentAsString(), SolveResponse.class);
        String planId = solveResponse.planId();

        mockMvc.perform(get("/plans/" + planId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planId", is(planId)))
                .andExpect(jsonPath("$.cabSchedules.CAB1", notNullValue()));

        mockMvc.perform(get("/plans/" + planId + "/validation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid", is(true)))
                .andExpect(jsonPath("$.violations", empty()));

        mockMvc.perform(get("/plans/" + planId + "/unassigned"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/plans/" + planId + "/vehicles/CAB1/timeline"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/plans/" + planId + "/vehicles/CAB2/timeline"))
                .andExpect(status().isOk());

        String cab1Content = mockMvc.perform(get("/plans/" + planId + "/vehicles/CAB1/timeline"))
                .andReturn().getResponse().getContentAsString();
        String activeCab = cab1Content.contains("TRIP") ? "CAB1" : "CAB2";

        mockMvc.perform(get("/plans/" + planId + "/vehicles/" + activeCab + "/timeline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))));
    }

    @Test
    @DisplayName("Non-existent plan returns HTTP 404 with sanitized ErrorResponse")
    void planNotFoundReturns404() throws Exception {
        mockMvc.perform(get("/plans/NON_EXISTENT_PLAN"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("PLAN_NOT_FOUND")))
                .andExpect(jsonPath("$.message", containsString("NON_EXISTENT_PLAN")))
                .andExpect(jsonPath("$.traceId", notNullValue()));
    }
}
