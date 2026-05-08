package com.foggy.navigator.business.agent.model.dto;

import com.foggy.navigator.business.agent.model.entity.BusinessFunctionVersionEntity;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class BusinessFunctionVersionDTO {
    private String tenantId;
    private String functionId;
    private String version;
    private String inputSchemaJson;
    private String outputSchemaJson;
    private String llmVisibleSummary;
    private String schemaVisibleSummary;
    // adapterConfigJson and manifestJson are intentionally omitted from control-plane DTO
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static BusinessFunctionVersionDTO fromEntity(BusinessFunctionVersionEntity entity) {
        if (entity == null) {
            return null;
        }
        BusinessFunctionVersionDTO dto = new BusinessFunctionVersionDTO();
        dto.setTenantId(entity.getTenantId());
        dto.setFunctionId(entity.getFunctionId());
        dto.setVersion(entity.getVersion());
        dto.setInputSchemaJson(entity.getInputSchemaJson());
        dto.setOutputSchemaJson(entity.getOutputSchemaJson());
        dto.setLlmVisibleSummary(entity.getLlmVisibleSummary());
        dto.setSchemaVisibleSummary(entity.getSchemaVisibleSummary());
        dto.setStatus(entity.getStatus());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
    }
}
