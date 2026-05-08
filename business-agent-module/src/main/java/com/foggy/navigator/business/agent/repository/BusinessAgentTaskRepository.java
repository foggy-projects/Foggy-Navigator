package com.foggy.navigator.business.agent.repository;

import com.foggy.navigator.business.agent.model.entity.BusinessAgentTaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BusinessAgentTaskRepository extends JpaRepository<BusinessAgentTaskEntity, Long> {

    Optional<BusinessAgentTaskEntity> findByTaskId(String taskId);

    Optional<BusinessAgentTaskEntity> findByTaskIdAndTenantId(String taskId, String tenantId);

    List<BusinessAgentTaskEntity> findBySessionIdAndTenantIdOrderByCreatedAtDesc(String sessionId, String tenantId);

    List<BusinessAgentTaskEntity> findByClientAppIdAndTenantIdOrderByCreatedAtDesc(String clientAppId, String tenantId);
}
