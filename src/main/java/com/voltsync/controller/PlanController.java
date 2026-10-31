package com.voltsync.controller;

import com.voltsync.controller.dto.SolveRequest;
import com.voltsync.controller.dto.SolveResponse;
import com.voltsync.domain.Event;
import com.voltsync.domain.Plan;
import com.voltsync.domain.UnassignedTrip;
import com.voltsync.domain.ValidationReport;
import com.voltsync.service.PlanningService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/plans")
public class PlanController {

    private final PlanningService planningService;

    public PlanController(PlanningService planningService) {
        this.planningService = planningService;
    }

    @PostMapping
    public ResponseEntity<SolveResponse> solveShift(@RequestBody(required = false) SolveRequest request) {
        SolveResponse response = planningService.solveShift(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Plan> getPlan(@PathVariable("id") String id) {
        return ResponseEntity.ok(planningService.getPlan(id));
    }

    @GetMapping("/{id}/validation")
    public ResponseEntity<ValidationReport> getValidationReport(@PathVariable("id") String id) {
        return ResponseEntity.ok(planningService.getValidationReport(id));
    }

    @GetMapping("/{id}/unassigned")
    public ResponseEntity<List<UnassignedTrip>> getUnassigned(@PathVariable("id") String id) {
        return ResponseEntity.ok(planningService.getUnassignedTrips(id));
    }

    @GetMapping("/{id}/vehicles/{vid}/timeline")
    public ResponseEntity<List<Event>> getVehicleTimeline(@PathVariable("id") String id,
                                                          @PathVariable("vid") String vid) {
        return ResponseEntity.ok(planningService.getVehicleTimeline(id, vid));
    }
}
