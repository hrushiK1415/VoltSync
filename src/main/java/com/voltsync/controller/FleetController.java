package com.voltsync.controller;

import com.voltsync.controller.dto.HubDto;
import com.voltsync.controller.dto.TripDto;
import com.voltsync.controller.dto.VehicleDto;
import com.voltsync.service.FleetService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class FleetController {

    private final FleetService fleetService;

    public FleetController(FleetService fleetService) {
        this.fleetService = fleetService;
    }

    @PostMapping("/fleet/vehicles")
    public ResponseEntity<Map<String, Object>> uploadVehicles(@RequestBody @Valid List<VehicleDto> vehicles) {
        int count = fleetService.saveVehicles(vehicles);
        return ResponseEntity.ok(Map.of("message", "Vehicles loaded successfully", "count", count));
    }

    @PostMapping("/fleet/hubs")
    public ResponseEntity<Map<String, Object>> uploadHubs(@RequestBody @Valid List<HubDto> hubs) {
        int count = fleetService.saveHubs(hubs);
        return ResponseEntity.ok(Map.of("message", "Hubs loaded successfully", "count", count));
    }

    @PostMapping("/trips")
    public ResponseEntity<Map<String, Object>> uploadTrips(@RequestBody @Valid List<TripDto> trips) {
        int count = fleetService.saveTrips(trips);
        return ResponseEntity.ok(Map.of("message", "Trips loaded successfully", "count", count));
    }
}
