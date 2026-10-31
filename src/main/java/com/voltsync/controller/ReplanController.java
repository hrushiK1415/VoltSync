package com.voltsync.controller;

import com.voltsync.controller.dto.BatteryDriftRequest;
import com.voltsync.controller.dto.ChargerFailureRequest;
import com.voltsync.domain.ReplanDiff;
import com.voltsync.service.ReplanService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/plans/{id}/events")
public class ReplanController {

    private final ReplanService replanService;

    public ReplanController(ReplanService replanService) {
        this.replanService = replanService;
    }

    @PostMapping("/battery-drift")
    public ResponseEntity<ReplanDiff> handleBatteryDrift(@PathVariable("id") String planId,
                                                         @RequestBody @Valid BatteryDriftRequest request) {
        ReplanDiff diff = replanService.handleBatteryDrift(planId, request);
        return ResponseEntity.ok(diff);
    }

    @PostMapping("/charger-failure")
    public ResponseEntity<ReplanDiff> handleChargerFailure(@PathVariable("id") String planId,
                                                           @RequestBody @Valid ChargerFailureRequest request) {
        ReplanDiff diff = replanService.handleChargerFailure(planId, request);
        return ResponseEntity.ok(diff);
    }
}
