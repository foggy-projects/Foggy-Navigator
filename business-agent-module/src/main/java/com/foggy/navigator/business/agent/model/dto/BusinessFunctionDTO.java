package com.foggy.navigator.business.agent.model.dto;

import com.foggy.navigator.business.agent.model.entity.BusinessFunctionEntity;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class BusinessFunctionDTO {
    private String tenantId;
    private String businessObjectId;
    private String functionId;
    private String domain;
    private String name;
    private String description;
    private String currentVersion;
    private String exposure;
    private String riskLevel;
    private Boolean approvalRequired;
    private Boolean idempotencyRequired;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static BusinessFunctionDTO fromEntity(BusinessFunctionEntity entity) {
        if (entity == null) {
            return null;
        }
        BusinessFunctionDTO dto = new BusinessFunctionDTO();
        dto.setTenantId(entity.getTenantId());
        dto.setBusinessObjectId(entity.getBusinessObjectId());
        dto.setFunctionId(entity.getFunctionId());
        dto.setDomain(entity.getDomain());
        dto.setName(entity.getName());
        dto.setDescription(entity.getDescription());
        dto.setCurrentVersion(entity.getCurrentVersion());
        dto.setExposure(entity.getExposure());
        dto.setRiskLevel(entity.getRiskLevel());
        dto.setApprovalRequired(entity.getApprovalRequired());
        dto.setIdempotencyRequired(entity.getIdempotencyRequired());
        dto.setStatus(entity.getStatus());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
    }
}
