package com.voltsync.service;

import com.voltsync.controller.dto.ChargerDto;
import com.voltsync.controller.dto.HubDto;
import com.voltsync.controller.dto.TripDto;
import com.voltsync.controller.dto.VehicleDto;
import com.voltsync.domain.*;
import com.voltsync.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Transactional
public class FleetService {

    private final VehicleRepository vehicleRepository;
    private final HubRepository hubRepository;
    private final ChargerRepository chargerRepository;
    private final TripRepository tripRepository;

    public FleetService(VehicleRepository vehicleRepository,
                        HubRepository hubRepository,
                        ChargerRepository chargerRepository,
                        TripRepository tripRepository) {
        this.vehicleRepository = vehicleRepository;
        this.hubRepository = hubRepository;
        this.chargerRepository = chargerRepository;
        this.tripRepository = tripRepository;
    }

    public int saveVehicles(List<VehicleDto> dtos) {
        List<VehicleEntity> entities = dtos.stream().map(dto -> new VehicleEntity(
                dto.id(), dto.tenantId(), dto.batteryKWh(), dto.socPct(),
                dto.consumptionKWhPerKm(), dto.lat(), dto.lng(), dto.hubId()
        )).toList();
        vehicleRepository.saveAll(entities);
        return entities.size();
    }

    public int saveHubs(List<HubDto> dtos) {
        List<HubEntity> hubEntities = new ArrayList<>();
        List<ChargerEntity> chargerEntities = new ArrayList<>();

        for (HubDto dto : dtos) {
            hubEntities.add(new HubEntity(dto.id(), dto.tenantId(), dto.lat(), dto.lng()));
            if (dto.chargers() != null) {
                for (ChargerDto c : dto.chargers()) {
                    String status = (c.status() != null && !c.status().isBlank()) ? c.status().toUpperCase() : "UP";
                    chargerEntities.add(new ChargerEntity(c.id(), dto.id(), c.powerKW(), status));
                }
            }
        }

        hubRepository.saveAll(hubEntities);
        chargerRepository.saveAll(chargerEntities);
        return hubEntities.size();
    }

    public int saveTrips(List<TripDto> dtos) {
        List<TripEntity> entities = dtos.stream().map(dto -> new TripEntity(
                dto.id(), dto.tenantId(), dto.startTime(),
                dto.startLat(), dto.startLng(), dto.endLat(), dto.endLng(),
                dto.distanceKm(), dto.latestFinish()
        )).toList();
        tripRepository.saveAll(entities);
        return entities.size();
    }

    @Transactional(readOnly = true)
    public List<Vehicle> getAllVehiclesDomain() {
        return vehicleRepository.findAll().stream().map(e -> new Vehicle(
                e.getId(), e.getTenantId(), e.getBatteryKWh(), e.getSocPct(),
                e.getConsumptionKWhPerKm(), new GeoLocation(e.getLat(), e.getLng()), e.getHubId()
        )).toList();
    }

    @Transactional(readOnly = true)
    public List<Hub> getAllHubsDomain() {
        List<HubEntity> hubEntities = hubRepository.findAll();
        List<ChargerEntity> chargerEntities = chargerRepository.findAll();

        Map<String, List<Charger>> chargersByHub = chargerEntities.stream()
                .map(c -> new Charger(
                        c.getId(), c.getHubId(), c.getPowerKW(),
                        "DOWN".equalsIgnoreCase(c.getStatus()) ? ChargerStatus.DOWN : ChargerStatus.UP
                ))
                .collect(Collectors.groupingBy(Charger::getHubId));

        return hubEntities.stream().map(h -> new Hub(
                h.getId(), h.getTenantId(), new GeoLocation(h.getLat(), h.getLng()),
                chargersByHub.getOrDefault(h.getId(), List.of())
        )).toList();
    }

    @Transactional(readOnly = true)
    public List<Trip> getAllTripsDomain() {
        return tripRepository.findAll().stream().map(t -> new Trip(
                t.getId(), t.getTenantId(), t.getStartTime(),
                new GeoLocation(t.getStartLat(), t.getStartLng()),
                new GeoLocation(t.getEndLat(), t.getEndLng()),
                t.getDistanceKm(), t.getLatestFinish()
        )).toList();
    }
}
