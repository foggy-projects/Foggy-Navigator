package com.foggy.navigator.business.agent.repository;

import com.foggy.navigator.business.agent.model.entity.BusinessObjectEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BusinessObjectRepository extends JpaRepository<BusinessObjectEntity, Long> {
    Optional<BusinessObjectEntity> findByObjectIdAndTenantId(String objectId, String tenantId);
    boolean existsByObjectIdAndTenantId(String objectId, String tenantId);
}
