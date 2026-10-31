# VoltSync - EV Fleet Charging & Trip Assignment Platform

VoltSync is an intelligent fleet management and charging orchestration platform designed to assign electric cab shifts to passenger trips and optimize charging schedules under strict battery, physical, and temporal constraints.

---

## 1. Problem Overview & Hard Rules

The platform solves an NP-hard vehicle scheduling problem with charging resource constraints under lexicographic objectives:
1. **Minimize trips sent to backup (petrol/diesel) cabs** (i.e. minimize unassigned EV trips).
2. **Minimize charging costs** by prioritizing off-peak tariff windows.

### Hard Constraints
1. **Battery Floor**: State of Charge (SoC) never drops below 15% at any time, including deadhead (empty) driving to trip origins and hub reserve requirements.
2. **Charger Capacity**: At no moment do more cabs charge concurrently at a charging hub than it has functional chargers.
3. **Trip Timing**: Every trip starts on time (vehicle at origin by `startTime`) and completes on or before `latestFinish`. Continuity of vehicle locations is strictly preserved.

### Live Re-Planning Targets
- **Battery Drift**: Detected SoC deficit triggers re-planning for the remainder of the shift in `< 2 s`, minimizing assignment perturbations.
- **Charger Failure**: When a physical charger breaks, only affected sessions are rerouted to neighboring chargers or hubs.
- **Scale Proof Target**: 300 electric cabs, 40 chargers, and 1,500 trips solved in `< 30 s` verified by an independent validator.

---

## 2. System Architecture & Tech Stack

- **Runtime & Language**: Java 17 + Spring Boot 3 (Spring Web, Spring Data JPA, Actuator, Validation)
- **Core Engine**: Plain Java domain classes without framework or DB couplings for blazing-fast unit testability and deterministic simulation.
- **Database**: PostgreSQL 16 (production/docker-compose) and H2 in-memory (automated test suites).
- **In-Process Cache**: Caffeine cache for plans and hub metadata (5-minute TTL).
- **Observability**: Micrometer + Prometheus Actuator (`/actuator/prometheus`), structured JSON logging with MDC trace/plan correlation.
- **Containerization**: Multi-stage `Dockerfile` and `docker-compose.yml`.

---

## 3. Charging Curve Physics & Analytical Integration

Fast DC charging follows a two-part piecewise linear charging model:
- **Fast Segment ($0\% \le \text{SoC} \le 80\%$):** Charges at full nominal rate $P_1 = \text{powerKW}$.
- **Taper Segment ($80\% < \text{SoC} \le 100\%$):** Charges at reduced taper rate $P_2 = \text{powerKW} \times 0.30$.

### Analytical Integration for Time-to-Charge
For a vehicle battery of capacity $B$ (kWh), the energy stored at SoC percentage $s$ is $E(s) = \frac{s}{100} B$.
Differentiating with respect to time $t$ (in hours):
$$\frac{dE}{dt} = \frac{B}{100} \frac{ds}{dt} = P(s) \implies dt = \frac{B}{100 \cdot P(s)} ds$$

Integrating between an initial state $s_{from}$ and target state $s_{to}$:
1. **Both states $\le 80\%$:**
   $$t = \frac{(s_{to} - s_{from}) \cdot B}{100 \cdot P_1} \text{ hours}$$
2. **Both states $\ge 80\%$:**
   $$t = \frac{(s_{to} - s_{from}) \cdot B}{100 \cdot P_2} \text{ hours}$$
3. **Spanning the $80\%$ knee point ($s_{from} < 80 < s_{to}$):**
   $$t = \frac{(80 - s_{from}) \cdot B}{100 \cdot P_1} + \frac{(s_{to} - 80) \cdot B}{100 \cdot P_2} \text{ hours}$$

Similarly, given a charging duration $\Delta t$, the resulting SoC is determined by allocating duration first to the fast charging segment up to the $80\%$ threshold and any remaining duration to the taper segment.

---

## 4. Assumptions (Case Study Model)

- **Average Speed**: 25 km/h base speed subject to temporal traffic multiplier curves during peak hours.
- **Distance Factor**: Haversine straight-line distance multiplied by a **1.3 road winding factor**.
- **Hub Charging Only**: Fast charging only occurs at designated physical charging hubs.
- **Full Charger Slot Occupation**: A vehicle occupies a charger stall for the entire duration of its charging session.
- **No Pooling**: Pre-scheduled trips are discrete, single-cab assignments (no ride-pooling).

---

## 5. Performance Benchmark & Latency Measurements

Benchmark executed using `BenchmarkRunner` on a full synthetic shift scenario: **300 electric cabs**, **40 DC fast chargers**, and **1,500 passenger trips** across a 16-hour shift.

### Host Machine Specifications
- **Operating System**: macOS (Darwin aarch64 - Apple Silicon)
- **CPU**: 8 cores
- **Allocated Heap**: 4,096 MB
- **Runtime**: OpenJDK 21 (Java 17/21 bytecode target)

### Measured Results

| Operation | Scale | Target SLA | Measured p50 | Measured p99 | Mean Latency | Throughput | Validation Report |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Shift Solve (Full)** | 300 cabs / 40 chargers / 1,500 trips | $< 30$ s | **3,447 ms** | **3,632 ms** | 3,506 ms | **17.1 solves/min** | **0 violations (Valid)** |
| **Incremental Re-plan** | Single cab battery drift / charger fail | $< 2,000$ ms | **2.0 ms** | **5.0 ms** | 2.0 ms | **473.9 replans/sec** | **0 violations (Valid)** |

- **Cost Optimality Gap**: $18.83\%$ relative to the theoretical lower bound (all energy charged off-peak with zero deadhead).
- **Reproducibility**: Run the automated benchmark test anytime:
  ```bash
  mvn test -Dtest=BenchmarkRunnerTest
  ```

---

## 6. Quickstart

### Prerequisites
- Java 17+
- Maven 3.9+
- Docker & Docker Compose (optional for containerized run)

### Running Locally with Maven
```bash
# Build and run tests
mvn clean test

# Run application locally (connects to PostgreSQL or defaults)
mvn spring-boot:run
```

### Running with Docker Compose
```bash
docker compose up --build
```
The application will be accessible at `http://localhost:8080`.
Prometheus metrics will be available at `http://localhost:8080/actuator/prometheus`.

---

## 7. Documentation & Demonstration Artifacts

- **System Design Note**: Detailed in [`DESIGN.md`](./DESIGN.md) covering the system architecture diagram, battery drift sequence diagram, 7-point failure-mode recovery table, and complexity scaling analysis.
- **Postman API Collection**: Pre-configured collection exportable in [`voltsync_postman_collection.json`](./voltsync_postman_collection.json) with automated plan ID capturing scripts.
- **cURL Demo Script**: Interactive shell walkthrough in [`scripts/demo_curl.sh`](./scripts/demo_curl.sh).

