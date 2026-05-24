package com.foggy.navigator.business.agent.model.dto;

import com.foggy.navigator.common.entity.AgentModelBindingEntity;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AgentModelBindingDTO {
    private Long id;
    private String tenantId;
    private String clientAppId;
    private String agentId;
    private String modelConfigId;
    private String modelConfigName;
    private String workerBackend;
    private boolean defaultModel;
    private LocalDateTime createdAt;

    public static AgentModelBindingDTO fromEntity(AgentModelBindingEntity entity) {
        AgentModelBindingDTO dto = new AgentModelBindingDTO();
        dto.setId(entity.getId());
        dto.setTenantId(entity.getTenantId());
        dto.setAgentId(entity.getAgentId());
        dto.setModelConfigId(entity.getModelConfigId());
        dto.setCreatedAt(entity.getCreatedAt());
        return dto;
    }
}
