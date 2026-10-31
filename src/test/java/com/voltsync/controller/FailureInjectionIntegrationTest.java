package com.voltsync.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voltsync.controller.dto.*;
import com.voltsync.domain.Plan;
import com.voltsync.domain.ValidationReport;
import com.voltsync.repository.*;
import com.voltsync.service.PlanningService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FailureInjectionIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlanningService planningService;

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
    void setUp() {
        processedEventRepository.deleteAll();
        planRepository.deleteAll();
        tripRepository.deleteAll();
        chargerRepository.deleteAll();
        hubRepository.deleteAll();
        vehicleRepository.deleteAll();
    }

    @Test
    @DisplayName("Failure Injection: Charger breaks mid-plan reroutes active session and preserves plan validity")
    void chargerBreaksMidPlanGracefulRecovery() throws Exception {
        ChargerDto ch1 = new ChargerDto("CH_FAIL_A", 50.0, "UP");
        ChargerDto ch2 = new ChargerDto("CH_FAIL_B", 50.0, "UP");
        HubDto hub = new HubDto("HUB_FAIL_INJECT", "TENANT_FAIL", 12.9500, 77.6000, List.of(ch1, ch2));
        mockMvc.perform(post("/fleet/hubs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(List.of(hub)))).andExpect(status().isOk());

        // Low SoC vehicle (20%) forces pre-trip charging at hub
        VehicleDto v1 = new VehicleDto("CAB_FAIL_1", "TENANT_FAIL", 60.0, 20.0, 0.18, 12.9500, 77.6000, "HUB_FAIL_INJECT");
        mockMvc.perform(post("/fleet/vehicles")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(List.of(v1)))).andExpect(status().isOk());

        Instant t0900 = Instant.parse("2026-10-31T09:00:00Z");
        TripDto trip = new TripDto("TRIP_FAIL_1", "TENANT_FAIL", t0900, 12.9500, 77.6000, 12.9141, 77.6109, 10.0, t0900.plusSeconds(3600));
        mockMvc.perform(post("/trips")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(List.of(trip)))).andExpect(status().isOk());

        MvcResult solveResult = mockMvc.perform(post("/plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SolveRequest("TENANT_FAIL", 5))))
                .andExpect(status().isOk())
                .andReturn();

        SolveResponse solveResponse = objectMapper.readValue(solveResult.getResponse().getContentAsString(), SolveResponse.class);
        String planId = solveResponse.planId();

        // Inject failure on CH_FAIL_A at 07:00 before trip start
        Instant failTime = Instant.parse("2026-10-31T07:00:00Z");
        ChargerFailureRequest failReq = new ChargerFailureRequest("EVT-CF-INJECT-1", "CH_FAIL_A", failTime);

        MvcResult replanResult = mockMvc.perform(post("/plans/" + planId + "/events/charger-failure")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(failReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planId", is(planId)))
                .andExpect(jsonPath("$.newVersion", is(2)))
                .andExpect(jsonPath("$.replanDurationMs", lessThan(2000)))
                .andReturn();

        // Independent validation report on the re-planned schedule
        mockMvc.perform(get("/plans/" + planId + "/validation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid", is(true)))
                .andExpect(jsonPath("$.violations", empty()));
    }

    @Test
    @DisplayName("Failure Injection: Solver time budget exhaustion returns partial best-so-far plan without failure")
    void solverTimeBudgetExhaustionReturnsPartialPlan() throws Exception {
        ChargerDto ch1 = new ChargerDto("CH_BUDGET", 50.0, "UP");
        HubDto hub = new HubDto("HUB_BUDGET", "TENANT_BUDGET", 12.9500, 77.6000, List.of(ch1));
        mockMvc.perform(post("/fleet/hubs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(List.of(hub)))).andExpect(status().isOk());

        VehicleDto v1 = new VehicleDto("CAB_BUDGET", "TENANT_BUDGET", 60.0, 90.0, 0.18, 12.9716, 77.5946, "HUB_BUDGET");
        mockMvc.perform(post("/fleet/vehicles")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(List.of(v1)))).andExpect(status().isOk());

        Instant t0800 = Instant.parse("2026-10-31T08:00:00Z");
        TripDto trip = new TripDto("TRIP_BUDGET", "TENANT_BUDGET", t0800, 12.9716, 77.5946, 12.9352, 77.6245, 5.0, t0800.plusSeconds(3600));
        mockMvc.perform(post("/trips")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(List.of(trip)))).andExpect(status().isOk());

        // Solves successfully with a tight budget (1 second)
        SolveRequest tightBudgetReq = new SolveRequest("TENANT_BUDGET", 1);
        MvcResult result = mockMvc.perform(post("/plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tightBudgetReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planId", notNullValue()))
                .andReturn();

        SolveResponse response = objectMapper.readValue(result.getResponse().getContentAsString(), SolveResponse.class);
        Plan plan = planningService.getPlan(response.planId());

        ValidationReport vr = planningService.getValidationReport(response.planId());
        assertThat(vr.valid()).isTrue();
    }
}
