package com.voltsync.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProcessedEventRepository extends JpaRepository<ProcessedEventEntity, String> {

    @Query("SELECT p FROM ProcessedEventEntity p WHERE p.vehicleId = :vehicleId ORDER BY p.eventTimestamp DESC")
    List<ProcessedEventEntity> findByVehicleIdOrderByTimestampDesc(@Param("vehicleId") String vehicleId);
}
