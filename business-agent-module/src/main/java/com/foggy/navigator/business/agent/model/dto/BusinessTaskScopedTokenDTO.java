package com.foggy.navigator.business.agent.model.dto;

import com.foggy.navigator.business.agent.model.entity.BusinessTaskScopedTokenEntity;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class BusinessTaskScopedTokenDTO {
    private String tokenId;
    private String taskId;
    private String sessionId;
    private String tenantId;
    private String clientAppId;
    private String upstreamUserId;
    private String navigatorEffectiveUserId;
    private String skillId;
    private String workerPoolId;
    private String modelConfigId;
    private String status;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static BusinessTaskScopedTokenDTO fromEntity(BusinessTaskScopedTokenEntity entity) {
        if (entity == null) {
            return null;
        }
        BusinessTaskScopedTokenDTO dto = new BusinessTaskScopedTokenDTO();
        dto.setTokenId(entity.getTokenId());
        dto.setTaskId(entity.getTaskId());
        dto.setSessionId(entity.getSessionId());
        dto.setTenantId(entity.getTenantId());
        dto.setClientAppId(entity.getClientAppId());
        dto.setUpstreamUserId(entity.getUpstreamUserId());
        dto.setNavigatorEffectiveUserId(entity.getNavigatorEffectiveUserId());
        dto.setSkillId(entity.getSkillId());
        dto.setWorkerPoolId(entity.getWorkerPoolId());
        dto.setModelConfigId(entity.getModelConfigId());
        dto.setStatus(entity.getStatus());
        dto.setExpiresAt(entity.getExpiresAt());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
    }
}
