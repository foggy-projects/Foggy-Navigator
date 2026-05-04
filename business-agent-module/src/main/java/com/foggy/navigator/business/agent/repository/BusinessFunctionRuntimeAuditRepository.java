package com.foggy.navigator.business.agent.repository;

import com.foggy.navigator.business.agent.model.entity.BusinessFunctionRuntimeAuditEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BusinessFunctionRuntimeAuditRepository extends JpaRepository<BusinessFunctionRuntimeAuditEntity, Long> {

    List<BusinessFunctionRuntimeAuditEntity> findByTenantIdAndTaskIdOrderByCreatedAtAsc(String tenantId, String taskId);

    List<BusinessFunctionRuntimeAuditEntity> findByTenantIdAndSessionIdOrderByCreatedAtAsc(String tenantId, String sessionId);

    List<BusinessFunctionRuntimeAuditEntity> findByTenantIdAndSuspendIdOrderByCreatedAtAsc(String tenantId, String suspendId);
}
