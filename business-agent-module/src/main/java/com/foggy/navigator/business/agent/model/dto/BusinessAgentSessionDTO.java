package com.foggy.navigator.business.agent.model.dto;

import com.foggy.navigator.business.agent.model.entity.BusinessAgentSessionEntity;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class BusinessAgentSessionDTO {
    private String tenantId;
    private String clientAppId;
    private String upstreamUserId;
    private String accountId;
    private String contextId;
    private String sessionId;
    private String skillId;
    private String agentId;
    private String title;
    private String latestTaskId;
    private String status;
    private String clientContextJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime lastAccessedAt;

    public static BusinessAgentSessionDTO fromEntity(BusinessAgentSessionEntity entity) {
        if (entity == null) {
            return null;
        }
        BusinessAgentSessionDTO dto = new BusinessAgentSessionDTO();
        dto.setTenantId(entity.getTenantId());
        dto.setClientAppId(entity.getClientAppId());
        dto.setUpstreamUserId(entity.getUpstreamUserId());
        dto.setAccountId(entity.getAccountId());
        dto.setContextId(entity.getContextId());
        dto.setSessionId(entity.getSessionId());
        dto.setSkillId(entity.getSkillId());
        dto.setAgentId(entity.getAgentId());
        dto.setLatestTaskId(entity.getLatestTaskId());
        dto.setStatus(entity.getStatus());
        dto.setClientContextJson(entity.getClientContextJson());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        dto.setLastAccessedAt(entity.getLastAccessedAt());
        return dto;
    }
}
