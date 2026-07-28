package com.foggy.navigator.business.agent.model.dto;

import com.foggy.navigator.business.agent.model.entity.BusinessTaskScopedTokenEntity;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class BusinessTaskScopedTokenDTO {
    private String tokenId;
    private String taskId;
    private String workerTaskId;
    private String workerSessionId;
    private String sessionId;
    private String tenantId;
    private String clientAppId;
    private String upstreamUserId;
    private String navigatorEffectiveUserId;
    private String navigatorInstanceId;
    private String callerAuthorityType;
    private String callerCredentialId;
    private String callerAccessTokenId;
    private String skillId;
    private String workerPoolId;
    private String modelConfigId;
    private String status;
    private Integer tokenVersion;
    private Integer generation;
    private String audience;
    private String identityAssurance;
    private String functionScopeJson;
    private String workerId;
    private String workerLeaseId;
    private LocalDateTime issuedAt;
    private LocalDateTime revokedAt;
    private String revokedBy;
    private String revokeReason;
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
        dto.setWorkerTaskId(entity.getWorkerTaskId());
        dto.setWorkerSessionId(entity.getWorkerSessionId());
        dto.setSessionId(entity.getSessionId());
        dto.setTenantId(entity.getTenantId());
        dto.setClientAppId(entity.getClientAppId());
        dto.setUpstreamUserId(entity.getUpstreamUserId());
        dto.setNavigatorEffectiveUserId(entity.getNavigatorEffectiveUserId());
        dto.setNavigatorInstanceId(entity.getNavigatorInstanceId());
        dto.setCallerAuthorityType(entity.getCallerAuthorityType());
        dto.setCallerCredentialId(entity.getCallerCredentialId());
        dto.setCallerAccessTokenId(entity.getCallerAccessTokenId());
        dto.setSkillId(entity.getSkillId());
        dto.setWorkerPoolId(entity.getWorkerPoolId());
        dto.setModelConfigId(entity.getModelConfigId());
        dto.setStatus(entity.getStatus());
        dto.setTokenVersion(entity.getTokenVersion());
        dto.setGeneration(entity.getGeneration());
        dto.setAudience(entity.getAudience());
        dto.setIdentityAssurance(entity.getIdentityAssurance());
        dto.setFunctionScopeJson(entity.getFunctionScopeJson());
        dto.setWorkerId(entity.getWorkerId());
        dto.setWorkerLeaseId(entity.getWorkerLeaseId());
        dto.setIssuedAt(entity.getIssuedAt());
        dto.setRevokedAt(entity.getRevokedAt());
        dto.setRevokedBy(entity.getRevokedBy());
        dto.setRevokeReason(entity.getRevokeReason());
        dto.setExpiresAt(entity.getExpiresAt());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
    }
}
