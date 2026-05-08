package com.foggy.navigator.business.agent.model.dto;

import com.foggy.navigator.business.agent.model.entity.BusinessFunctionRuntimeAuditEntity;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * DTO for runtime audit records.
 * Does NOT expose plain task-scoped token, adapterConfigJson, or manifestJson.
 */
@Data
public class BusinessFunctionRuntimeAuditDTO {
    private String auditId;
    private String tenantId;
    private String clientAppId;
    private String upstreamUserId;
    private String taskId;
    private String sessionId;
    private String workerPoolId;
    private String skillId;
    private String functionId;
    private String functionVersion;
    private String suspendId;
    private String eventType;
    private String status;
    private String inputHash;
    private String outputHash;
    private String errorCode;
    private String errorMessage;
    private Long durationMs;
    private LocalDateTime createdAt;

    public static BusinessFunctionRuntimeAuditDTO fromEntity(BusinessFunctionRuntimeAuditEntity entity) {
        if (entity == null) {
            return null;
        }
        BusinessFunctionRuntimeAuditDTO dto = new BusinessFunctionRuntimeAuditDTO();
        dto.setAuditId(entity.getAuditId());
        dto.setTenantId(entity.getTenantId());
        dto.setClientAppId(entity.getClientAppId());
        dto.setUpstreamUserId(entity.getUpstreamUserId());
        dto.setTaskId(entity.getTaskId());
        dto.setSessionId(entity.getSessionId());
        dto.setWorkerPoolId(entity.getWorkerPoolId());
        dto.setSkillId(entity.getSkillId());
        dto.setFunctionId(entity.getFunctionId());
        dto.setFunctionVersion(entity.getFunctionVersion());
        dto.setSuspendId(entity.getSuspendId());
        dto.setEventType(entity.getEventType());
        dto.setStatus(entity.getStatus());
        dto.setInputHash(entity.getInputHash());
        dto.setOutputHash(entity.getOutputHash());
        dto.setErrorCode(entity.getErrorCode());
        dto.setErrorMessage(entity.getErrorMessage());
        dto.setDurationMs(entity.getDurationMs());
        dto.setCreatedAt(entity.getCreatedAt());
        return dto;
    }
}
