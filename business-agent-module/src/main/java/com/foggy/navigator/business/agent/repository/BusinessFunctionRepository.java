package com.foggy.navigator.business.agent.repository;

import com.foggy.navigator.business.agent.model.entity.BusinessFunctionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BusinessFunctionRepository extends JpaRepository<BusinessFunctionEntity, Long> {
    Optional<BusinessFunctionEntity> findByTenantIdAndFunctionId(String tenantId, String functionId);
    List<BusinessFunctionEntity> findByTenantId(String tenantId);
}
