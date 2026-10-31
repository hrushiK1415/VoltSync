package com.voltsync.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface HubRepository extends JpaRepository<HubEntity, String> {
    List<HubEntity> findByTenantId(String tenantId);
}
