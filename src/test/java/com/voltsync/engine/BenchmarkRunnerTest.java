package com.voltsync.engine;

import com.voltsync.domain.Plan;
import com.voltsync.domain.ValidationReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BenchmarkRunnerTest {

    @Test
    @DisplayName("Performance SLA: 300 cabs / 40 chargers / 1500 trips solves in < 30s with valid validator report")
    void solveBenchmarkSatisfiesSla() {
        BenchmarkRunner.MachineSpecs specs = BenchmarkRunner.MachineSpecs.detect();
        assertThat(specs.cores()).isGreaterThan(0);
        assertThat(specs.maxMemoryMb()).isGreaterThan(0);

        SyntheticDataGenerator generator = new SyntheticDataGenerator();
        SyntheticDataGenerator.Scenario scenario = generator.generateBenchmarkScenario(42L, 300, 40, 1500);

        GreedyLocalSearchSolver solver = new GreedyLocalSearchSolver();
        PlanValidator validator = new PlanValidator();

        long start = System.currentTimeMillis();
        Plan plan = solver.solve(new Solver.SolverInput(
                "BENCH-SLA-TEST",
                scenario.vehicles(), scenario.hubs(), scenario.trips(),
                scenario.tariff(), scenario.model(), Duration.ofSeconds(15)
        ));
        long durationMs = System.currentTimeMillis() - start;

        // SLA: must solve 300/40/1500 in < 30 seconds
        assertThat(durationMs).isLessThan(30_000);

        ValidationReport report = validator.validate(plan, scenario.vehicles(), scenario.hubs(), scenario.trips());
        assertThat(report.valid()).isTrue();
        assertThat(report.violations()).isEmpty();
    }

    @Test
    @DisplayName("Performance SLA: Incremental re-planning p99 latency is < 2000 ms across iterations")
    void replanBenchmarkSatisfiesSla() {
        SyntheticDataGenerator generator = new SyntheticDataGenerator();
        SyntheticDataGenerator.Scenario scenario = generator.generateBenchmarkScenario(42L, 300, 40, 1500);

        GreedyLocalSearchSolver solver = new GreedyLocalSearchSolver();
        Plan plan = solver.solve(new Solver.SolverInput(
                "BENCH-REPLAN-SLA",
                scenario.vehicles(), scenario.hubs(), scenario.trips(),
                scenario.tariff(), scenario.model(), Duration.ofSeconds(15)
        ));

        IncrementalReplanner replanner = new IncrementalReplanner();
        List<Long> latencies = new ArrayList<>();

        List<String> activeCabs = plan.getCabSchedules().entrySet().stream()
                .filter(e -> !e.getValue().isEmpty())
                .map(Map.Entry::getKey)
                .toList();

        Instant baseTime = Instant.parse("2026-10-31T09:00:00Z");
        Plan currentPlan = plan;

        for (int i = 0; i < 10; i++) {
            String cabId = activeCabs.get(i % activeCabs.size());
            ChargerTimeline timeline = new ChargerTimeline();
            Instant atTime = baseTime.plusSeconds(i * 300);

            long start = System.currentTimeMillis();
            var result = replanner.handleBatteryDrift(
                    currentPlan, cabId, 22.0, atTime,
                    scenario.vehicles(), scenario.hubs(), scenario.trips(),
                    scenario.tariff(), scenario.model(), timeline
            );
            long elapsed = System.currentTimeMillis() - start;
            latencies.add(elapsed);
            currentPlan = result.plan();
        }

        BenchmarkRunner.LatencyStats stats = BenchmarkRunner.computeStats(latencies, 1.0);
        // SLA: p99 must be strictly under 2,000 ms (typically 1-5 ms)
        assertThat(stats.p99Ms()).isLessThan(2000.0);
    }
}
