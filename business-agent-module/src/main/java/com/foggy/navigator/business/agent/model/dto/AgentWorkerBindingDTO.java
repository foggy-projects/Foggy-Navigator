package com.foggy.navigator.business.agent.model.dto;

import com.foggy.navigator.common.entity.AgentWorkerBindingEntity;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AgentWorkerBindingDTO {
    private Long id;
    private String tenantId;
    private String agentId;
    private String workerPoolId;
    private String clientAppId;
    private String workerPoolName;
    private String workerBackend;
    private String routingPolicy;
    private ResourceOwnerType workerPoolOwnerType;
    private String workerPoolOwnerId;
    private Boolean defaultWorkerPool;
    private String status;
    private String healthStatus;
    private LocalDateTime createdAt;

    public static AgentWorkerBindingDTO fromEntity(AgentWorkerBindingEntity entity) {
        AgentWorkerBindingDTO dto = new AgentWorkerBindingDTO();
        dto.setId(entity.getId());
        dto.setTenantId(entity.getTenantId());
        dto.setAgentId(entity.getAgentId());
        dto.setWorkerPoolId(entity.getWorkerPoolId());
        dto.setCreatedAt(entity.getCreatedAt());
        return dto;
    }
}
