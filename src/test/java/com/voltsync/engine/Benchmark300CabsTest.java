package com.voltsync.engine;

import com.voltsync.domain.Plan;
import com.voltsync.domain.ValidationReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class Benchmark300CabsTest {

    private final SyntheticDataGenerator generator = new SyntheticDataGenerator();
    private final GreedyLocalSearchSolver solver = new GreedyLocalSearchSolver();
    private final PlanValidator validator = new PlanValidator();

    @Test
    @DisplayName("Proof Target: 300 cabs, 40 chargers, 1,500 trips solved in < 30s with valid PlanValidator report")
    void solve300CabsBenchmark() {
        long seed = 42L;
        int numCabs = 300;
        int numChargers = 40;
        int numTrips = 1500;

        SyntheticDataGenerator.Scenario scenario = generator.generateBenchmarkScenario(
                seed, numCabs, numChargers, numTrips
        );

        assertThat(scenario.vehicles()).hasSize(numCabs);
        assertThat(scenario.trips()).hasSize(numTrips);

        Solver.SolverInput input = new Solver.SolverInput(
                "PLAN_BENCHMARK_300",
                scenario.vehicles(),
                scenario.hubs(),
                scenario.trips(),
                scenario.tariff(),
                scenario.model(),
                Duration.ofSeconds(20) // 20s budget, strictly < 30s
        );

        long startNs = System.nanoTime();
        Plan plan = solver.solve(input);
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;

        System.out.println("==================================================");
        System.out.println("300 Cabs / 40 Chargers / 1,500 Trips Benchmark Result:");
        System.out.println("Solve time: " + elapsedMs + " ms");
        System.out.println("Total Cost: $" + String.format("%.2f", plan.getCost()));
        System.out.println("Cost Lower Bound: $" + String.format("%.2f", plan.getLowerBound()));
        System.out.println("Optimality Gap: " + String.format("%.2f%%", plan.getOptimalityGap() * 100.0));
        System.out.println("Unassigned trips: " + plan.getUnassigned().size() + " / " + numTrips);
        System.out.println("==================================================");

        // 1. Must solve strictly under 30 seconds
        assertThat(elapsedMs).isLessThan(30_000);

        // 2. Validate plan with independent validator
        ValidationReport report = validator.validate(
                plan, scenario.vehicles(), scenario.hubs(), scenario.trips()
        );

        if (!report.valid()) {
            System.err.println("Validator report violations (" + report.violations().size() + "):");
            report.violations().stream().limit(10).forEach(v -> System.err.println(" - " + v));
        }

        assertThat(report.valid())
                .withFailMessage("Independent PlanValidator found %d violations", report.violations().size())
                .isTrue();

        // 3. Lower bound and unassigned reasons must be reported
        assertThat(plan.getLowerBound()).isGreaterThan(0.0);
        assertThat(plan.getOptimalityGap()).isGreaterThanOrEqualTo(0.0);
    }
}
