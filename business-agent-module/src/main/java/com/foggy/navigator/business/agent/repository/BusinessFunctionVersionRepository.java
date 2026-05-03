package com.foggy.navigator.business.agent.repository;

import com.foggy.navigator.business.agent.model.entity.BusinessFunctionVersionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BusinessFunctionVersionRepository extends JpaRepository<BusinessFunctionVersionEntity, Long> {
    Optional<BusinessFunctionVersionEntity> findByTenantIdAndFunctionIdAndVersion(String tenantId, String functionId, String version);
    List<BusinessFunctionVersionEntity> findByTenantIdAndFunctionId(String tenantId, String functionId);
}
