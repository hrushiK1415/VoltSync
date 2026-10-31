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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConcurrencyReplanIntegrationTest {

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
    void setUp() {
        processedEventRepository.deleteAll();
        planRepository.deleteAll();
        tripRepository.deleteAll();
        chargerRepository.deleteAll();
        hubRepository.deleteAll();
        vehicleRepository.deleteAll();
    }

    @Test
    @DisplayName("Two parallel re-plans against same plan version: one succeeds (HTTP 200), one fails with conflict (HTTP 409)")
    void parallelReplansOptimisticLocking() throws Exception {
        ChargerDto ch1 = new ChargerDto("CH_CONC_1", 50.0, "UP");
        ChargerDto ch2 = new ChargerDto("CH_CONC_2", 50.0, "UP");
        HubDto hub = new HubDto("HUB_CONC", "TENANT_CONC", 12.9500, 77.6000, List.of(ch1, ch2));
        mockMvc.perform(post("/fleet/hubs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(List.of(hub)))).andExpect(status().isOk());

        VehicleDto v1 = new VehicleDto("CAB_CONC_1", "TENANT_CONC", 60.0, 90.0, 0.18, 12.9716, 77.5946, "HUB_CONC");
        VehicleDto v2 = new VehicleDto("CAB_CONC_2", "TENANT_CONC", 60.0, 90.0, 0.18, 12.9716, 77.5946, "HUB_CONC");
        mockMvc.perform(post("/fleet/vehicles")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(List.of(v1, v2)))).andExpect(status().isOk());

        Instant t0800 = Instant.parse("2026-10-31T08:00:00Z");
        TripDto trip1 = new TripDto("TRIP_CONC_1", "TENANT_CONC", t0800, 12.9716, 77.5946, 12.9352, 77.6245, 5.0, t0800.plusSeconds(3600));
        mockMvc.perform(post("/trips")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(List.of(trip1)))).andExpect(status().isOk());

        MvcResult solveResult = mockMvc.perform(post("/plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SolveRequest("TENANT_CONC", 5))))
                .andExpect(status().isOk())
                .andReturn();

        SolveResponse solveResponse = objectMapper.readValue(solveResult.getResponse().getContentAsString(), SolveResponse.class);
        String planId = solveResponse.planId();

        Instant t0830 = Instant.parse("2026-10-31T08:30:00Z");
        BatteryDriftRequest reqA = new BatteryDriftRequest("EVT-CONC-A", "CAB_CONC_1", 70.0, t0830);
        BatteryDriftRequest reqB = new BatteryDriftRequest("EVT-CONC-B", "CAB_CONC_2", 70.0, t0830);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(2);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        Callable<Integer> taskA = () -> {
            startLatch.await();
            try {
                MvcResult res = mockMvc.perform(post("/plans/" + planId + "/events/battery-drift")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqA))).andReturn();
                int status = res.getResponse().getStatus();
                if (status == 200) successCount.incrementAndGet();
                if (status == 409) conflictCount.incrementAndGet();
                return status;
            } finally {
                finishLatch.countDown();
            }
        };

        Callable<Integer> taskB = () -> {
            startLatch.await();
            try {
                MvcResult res = mockMvc.perform(post("/plans/" + planId + "/events/battery-drift")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqB))).andReturn();
                int status = res.getResponse().getStatus();
                if (status == 200) successCount.incrementAndGet();
                if (status == 409) conflictCount.incrementAndGet();
                return status;
            } finally {
                finishLatch.countDown();
            }
        };

        Future<Integer> fA = executor.submit(taskA);
        Future<Integer> fB = executor.submit(taskB);

        // Release latch to trigger both HTTP requests simultaneously
        startLatch.countDown();
        finishLatch.await(10, TimeUnit.SECONDS);

        executor.shutdown();

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(1);

        PlanEntity finalEntity = planRepository.findById(planId).orElseThrow();
        assertThat(finalEntity.getVersion()).isEqualTo(2);
    }
}
