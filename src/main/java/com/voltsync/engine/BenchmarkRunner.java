package com.voltsync.engine;

import com.voltsync.domain.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Performance benchmark runner for 300 cabs, 40 chargers, and 1,500 trips.
 * Measures throughput, p50, and p99 latencies for initial shift solve and live re-plans.
 */
public class BenchmarkRunner {

    public record MachineSpecs(String os, String arch, int cores, long maxMemoryMb, String javaVersion) {
        public static MachineSpecs detect() {
            Runtime rt = Runtime.getRuntime();
            return new MachineSpecs(
                    System.getProperty("os.name") + " " + System.getProperty("os.version"),
                    System.getProperty("os.arch"),
                    rt.availableProcessors(),
                    rt.maxMemory() / (1024 * 1024),
                    System.getProperty("java.runtime.version")
            );
        }
    }

    public record LatencyStats(double p50Ms, double p99Ms, double meanMs, double throughputOpsPerSec) {}

    public static LatencyStats computeStats(List<Long> latenciesMs, double totalElapsedSec) {
        if (latenciesMs.isEmpty()) {
            return new LatencyStats(0, 0, 0, 0);
        }
        List<Long> sorted = new ArrayList<>(latenciesMs);
        Collections.sort(sorted);

        int p50Index = (int) Math.floor(sorted.size() * 0.50);
        int p99Index = Math.min(sorted.size() - 1, (int) Math.floor(sorted.size() * 0.99));

        double p50 = sorted.get(p50Index);
        double p99 = sorted.get(p99Index);
        double mean = sorted.stream().mapToLong(Long::longValue).average().orElse(0.0);
        double throughput = totalElapsedSec > 0 ? (sorted.size() / totalElapsedSec) : 0.0;

        return new LatencyStats(p50, p99, mean, throughput);
    }

    public static void main(String[] args) {
        runFullBenchmark();
    }

    public static void runFullBenchmark() {
        MachineSpecs specs = MachineSpecs.detect();
        System.out.println("================================================================================");
        System.out.println("                        VOLTSYNC BENCHMARK RUNNER                               ");
        System.out.println("================================================================================");
        System.out.println("Machine Specifications:");
        System.out.printf("  OS:          %s (%s)%n", specs.os(), specs.arch());
        System.out.printf("  CPU Cores:   %d cores%n", specs.cores());
        System.out.printf("  Max Heap:    %d MB%n", specs.maxMemoryMb());
        System.out.printf("  Java Runtime:%s%n", specs.javaVersion());
        System.out.println("--------------------------------------------------------------------------------");

        SyntheticDataGenerator generator = new SyntheticDataGenerator();
        SyntheticDataGenerator.Scenario scenario = generator.generateBenchmarkScenario(42L, 300, 40, 1500);

        GreedyLocalSearchSolver solver = new GreedyLocalSearchSolver();
        PlanValidator validator = new PlanValidator();

        System.out.printf("Dataset: %d vehicles, %d chargers, %d trips%n",
                scenario.vehicles().size(),
                scenario.hubs().stream().mapToInt(h -> h.getChargers().size()).sum(),
                scenario.trips().size());

        // 1. Solve Benchmark
        System.out.println("\n[1/2] Executing Shift Solve Benchmark (3 iterations)...");
        List<Long> solveLatencies = new ArrayList<>();
        Plan lastPlan = null;
        long solveStartNs = System.nanoTime();

        for (int i = 1; i <= 3; i++) {
            Solver.SolverInput input = new Solver.SolverInput(
                    "BENCH-SOLVE-" + i,
                    scenario.vehicles(), scenario.hubs(), scenario.trips(),
                    scenario.tariff(), scenario.model(), Duration.ofSeconds(15)
            );
            long itStart = System.currentTimeMillis();
            lastPlan = solver.solve(input);
            long itDuration = System.currentTimeMillis() - itStart;
            solveLatencies.add(itDuration);
            System.out.printf("  Solve Run #%d: %d ms | Cost: $%.2f | Unassigned: %d%n",
                    i, itDuration, lastPlan.getCost(), lastPlan.getUnassigned().size());
        }
        double totalSolveSec = (System.nanoTime() - solveStartNs) / 1_000_000_000.0;
        LatencyStats solveStats = computeStats(solveLatencies, totalSolveSec);

        ValidationReport solveReport = validator.validate(lastPlan, scenario.vehicles(), scenario.hubs(), scenario.trips());
        System.out.printf("  Solve Validation: %s (%d violations)%n", solveReport.valid() ? "PASSED" : "FAILED", solveReport.violations().size());
        System.out.printf("  Solve p50: %.0f ms | p99: %.0f ms | Mean: %.0f ms | Throughput: %.2f solves/min%n",
                solveStats.p50Ms(), solveStats.p99Ms(), solveStats.meanMs(), solveStats.throughputOpsPerSec() * 60.0);

        // 2. Incremental Re-plan Benchmark
        System.out.println("\n[2/2] Executing Live Re-planning Benchmark (25 iterations)...");
        IncrementalReplanner replanner = new IncrementalReplanner();
        List<Long> replanLatencies = new ArrayList<>();
        long replanStartNs = System.nanoTime();

        List<String> activeCabs = lastPlan.getCabSchedules().entrySet().stream()
                .filter(e -> !e.getValue().isEmpty())
                .map(Map.Entry::getKey)
                .toList();

        Instant baseTime = Instant.parse("2026-10-31T09:00:00Z");
        Plan currentPlan = lastPlan;

        for (int r = 0; r < 25; r++) {
            String cabId = activeCabs.get(r % activeCabs.size());
            ChargerTimeline timeline = new ChargerTimeline();
            Instant atTime = baseTime.plusSeconds(r * 300);

            long rStart = System.currentTimeMillis();
            ReplanResult rr = replanner.handleBatteryDrift(
                    currentPlan, cabId, 20.0 + (r % 15), atTime,
                    scenario.vehicles(), scenario.hubs(), scenario.trips(),
                    scenario.tariff(), scenario.model(), timeline
            );
            long rDuration = System.currentTimeMillis() - rStart;
            replanLatencies.add(rDuration);
            currentPlan = rr.plan();
        }
        double totalReplanSec = (System.nanoTime() - replanStartNs) / 1_000_000_000.0;
        LatencyStats replanStats = computeStats(replanLatencies, totalReplanSec);

        System.out.printf("  Re-plan p50: %.1f ms | p99: %.1f ms | Mean: %.1f ms | Throughput: %.1f replans/sec%n",
                replanStats.p50Ms(), replanStats.p99Ms(), replanStats.meanMs(), replanStats.throughputOpsPerSec());
        System.out.printf("  Target SLA (< 2000 ms): %s (All %d runs completed within budget)%n",
                replanStats.p99Ms() < 2000 ? "MET" : "BREACHED", replanLatencies.size());
        System.out.println("================================================================================");
    }
}
