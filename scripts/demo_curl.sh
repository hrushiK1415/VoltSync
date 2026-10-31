#!/usr/bin/env bash
# ==============================================================================
# VoltSync — Interactive API Demo Script
# Demonstrates: Fleet Ingestion, Shift Solve, Constraint Validation,
#               Cab Timelines, Battery Drift Re-plan, Idempotency & Fault Injection.
# ==============================================================================

set -e

BASE_URL="${BASE_URL:-http://localhost:8080}"
TENANT_ID="DEMO_TENANT"

# Colors for terminal output
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo -e "${BLUE}================================================================${NC}"
echo -e "${BLUE}        VoltSync: EV Fleet Charging & Trip Assignment Demo       ${NC}"
echo -e "${BLUE}================================================================${NC}"
echo -e "Target Server: ${BASE_URL}\n"

# 1. Health & Actuator Check
echo -e "${YELLOW}[1/8] Verifying Service Health & Actuator Endpoints...${NC}"
curl -s -X GET "${BASE_URL}/actuator/health" | jq .
echo ""

# 2. Ingest Hubs & Chargers
echo -e "${YELLOW}[2/8] Ingesting Charging Hubs and 50kW Fast Chargers...${NC}"
curl -s -X POST "${BASE_URL}/fleet/hubs" \
  -H "Content-Type: application/json" \
  -d '[
    {
      "id": "HUB_CENTRAL",
      "tenantId": "'"${TENANT_ID}"'",
      "lat": 12.9716,
      "lng": 77.5946,
      "chargers": [
        {"id": "CH_FAST_01", "powerKW": 50.0, "status": "UP"},
        {"id": "CH_FAST_02", "powerKW": 50.0, "status": "UP"}
      ]
    },
    {
      "id": "HUB_AIRPORT",
      "tenantId": "'"${TENANT_ID}"'",
      "lat": 13.1986,
      "lng": 77.7066,
      "chargers": [
        {"id": "CH_AIR_01", "powerKW": 60.0, "status": "UP"}
      ]
    }
  ]' | jq .
echo ""

# 3. Ingest Electric Vehicles
echo -e "${YELLOW}[3/8] Ingesting Electric Vehicles (60 kWh battery, 90% SoC)...${NC}"
curl -s -X POST "${BASE_URL}/fleet/vehicles" \
  -H "Content-Type: application/json" \
  -d '[
    {
      "id": "CAB_01",
      "tenantId": "'"${TENANT_ID}"'",
      "batteryKWh": 60.0,
      "socPct": 90.0,
      "consumptionKWhPerKm": 0.18,
      "lat": 12.9716,
      "lng": 77.5946,
      "hubId": "HUB_CENTRAL"
    },
    {
      "id": "CAB_02",
      "tenantId": "'"${TENANT_ID}"'",
      "batteryKWh": 60.0,
      "socPct": 85.0,
      "consumptionKWhPerKm": 0.18,
      "lat": 12.9716,
      "lng": 77.5946,
      "hubId": "HUB_CENTRAL"
    }
  ]' | jq .
echo ""

# 4. Ingest Commuter Shift Trips
echo -e "${YELLOW}[4/8] Ingesting Commuter Shift Trips...${NC}"
curl -s -X POST "${BASE_URL}/trips" \
  -H "Content-Type: application/json" \
  -d '[
    {
      "id": "TRIP_01",
      "tenantId": "'"${TENANT_ID}"'",
      "startTime": "2026-10-31T08:00:00Z",
      "startLat": 12.9716,
      "startLng": 77.5946,
      "endLat": 12.9352,
      "endLng": 77.6245,
      "distanceKm": 8.0,
      "latestFinish": "2026-10-31T08:45:00Z"
    },
    {
      "id": "TRIP_02",
      "tenantId": "'"${TENANT_ID}"'",
      "startTime": "2026-10-31T09:00:00Z",
      "startLat": 12.9352,
      "startLng": 77.6245,
      "endLat": 12.9141,
      "endLng": 77.6109,
      "distanceKm": 6.5,
      "latestFinish": "2026-10-31T09:40:00Z"
    },
    {
      "id": "TRIP_03",
      "tenantId": "'"${TENANT_ID}"'",
      "startTime": "2026-10-31T10:00:00Z",
      "startLat": 12.9141,
      "startLng": 77.6109,
      "endLat": 12.9716,
      "endLng": 77.5946,
      "distanceKm": 11.0,
      "latestFinish": "2026-10-31T10:50:00Z"
    }
  ]' | jq .
echo ""

# 5. Generate Shift Plan via Core Optimization Solver
echo -e "${YELLOW}[5/8] Solving Shift Plan via Greedy Local Search Optimizer...${NC}"
PLAN_RESPONSE=$(curl -s -X POST "${BASE_URL}/plans" \
  -H "Content-Type: application/json" \
  -d '{
    "tenantId": "'"${TENANT_ID}"'",
    "timeBudgetSeconds": 10
  }')

echo "${PLAN_RESPONSE}" | jq .
PLAN_ID=$(echo "${PLAN_RESPONSE}" | jq -r .planId)
echo -e "${GREEN}Generated Plan ID: ${PLAN_ID}${NC}\n"

# 6. Audit & Explainability Endpoints
echo -e "${YELLOW}[6/8] Auditing Hard Physical Constraints & Explanations...${NC}"
echo -e "${BLUE}--> Independent Constraint Validation Report:${NC}"
curl -s -X GET "${BASE_URL}/plans/${PLAN_ID}/validation" | jq .

echo -e "\n${BLUE}--> Unassigned Trips & Root Cause Classification:${NC}"
curl -s -X GET "${BASE_URL}/plans/${PLAN_ID}/unassigned" | jq .

echo -e "\n${BLUE}--> CAB_01 Step-by-Step Chronological Timeline:${NC}"
curl -s -X GET "${BASE_URL}/plans/${PLAN_ID}/vehicles/CAB_01/timeline" | jq .
echo ""

# 7. Live Battery Drift Re-Plan (Simulating Traffic Delay & SoC Drop)
echo -e "${YELLOW}[7/8] Triggering Live Battery Drift Event (Cab arrives at 22% SoC instead of planned)...${NC}"
DRIFT_EVENT_ID="EVT-DRIFT-$(date +%s)"
DRIFT_RESPONSE=$(curl -s -X POST "${BASE_URL}/plans/${PLAN_ID}/events/battery-drift" \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "'"${DRIFT_EVENT_ID}"'",
    "vehicleId": "CAB_01",
    "soc": 22.0,
    "at": "2026-10-31T08:50:00Z"
  }')
echo "${DRIFT_RESPONSE}" | jq .

echo -e "\n${BLUE}--> Testing Idempotency: Re-submitting identical event ID...${NC}"
curl -s -X POST "${BASE_URL}/plans/${PLAN_ID}/events/battery-drift" \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "'"${DRIFT_EVENT_ID}"'",
    "vehicleId": "CAB_01",
    "soc": 22.0,
    "at": "2026-10-31T08:50:00Z"
  }' | jq .
echo ""

# 8. Charger Breakdown Fault Injection
echo -e "${YELLOW}[8/8] Injecting Mid-Shift Charger Hardware Failure (CH_FAST_01 breaks)...${NC}"
FAIL_EVENT_ID="EVT-FAIL-$(date +%s)"
curl -s -X POST "${BASE_URL}/plans/${PLAN_ID}/events/charger-failure" \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "'"${FAIL_EVENT_ID}"'",
    "chargerId": "CH_FAST_01",
    "at": "2026-10-31T08:55:00Z"
  }' | jq .

echo -e "\n${GREEN}================================================================${NC}"
echo -e "${GREEN}             VoltSync Interactive Demo Completed!               ${NC}"
echo -e "${GREEN}================================================================${NC}"
