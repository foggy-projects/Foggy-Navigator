package com.foggy.navigator.business.agent.model.dto;

import com.foggy.navigator.business.agent.model.entity.BusinessAgentTaskEntity;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class BusinessAgentTaskDTO {
    private String taskId;
    private String sessionId;
    private String tenantId;
    private String clientAppId;
    private String upstreamUserId;
    private String navigatorEffectiveUserId;
    private String skillId;
    private String workerPoolId;
    private String modelConfigId;
    private String requestedModelConfigId;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static BusinessAgentTaskDTO fromEntity(BusinessAgentTaskEntity entity) {
        if (entity == null) {
            return null;
        }
        BusinessAgentTaskDTO dto = new BusinessAgentTaskDTO();
        dto.setTaskId(entity.getTaskId());
        dto.setSessionId(entity.getSessionId());
        dto.setTenantId(entity.getTenantId());
        dto.setClientAppId(entity.getClientAppId());
        dto.setUpstreamUserId(entity.getUpstreamUserId());
        dto.setNavigatorEffectiveUserId(entity.getNavigatorEffectiveUserId());
        dto.setSkillId(entity.getSkillId());
        dto.setWorkerPoolId(entity.getWorkerPoolId());
        dto.setModelConfigId(entity.getModelConfigId());
        dto.setRequestedModelConfigId(entity.getRequestedModelConfigId());
        dto.setStatus(entity.getStatus());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
    }
}
