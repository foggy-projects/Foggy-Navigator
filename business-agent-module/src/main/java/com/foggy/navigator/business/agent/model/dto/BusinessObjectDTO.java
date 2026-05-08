package com.foggy.navigator.business.agent.model.dto;

import com.foggy.navigator.business.agent.model.entity.BusinessObjectEntity;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class BusinessObjectDTO {
    private String objectId;
    private String tenantId;
    private String name;
    private String description;
    private String domain;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String createdBy;
    private String updatedBy;

    public static BusinessObjectDTO fromEntity(BusinessObjectEntity entity) {
        if (entity == null) {
            return null;
        }
        BusinessObjectDTO dto = new BusinessObjectDTO();
        dto.setObjectId(entity.getObjectId());
        dto.setTenantId(entity.getTenantId());
        dto.setName(entity.getName());
        dto.setDescription(entity.getDescription());
        dto.setDomain(entity.getDomain());
        dto.setStatus(entity.getStatus());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        dto.setCreatedBy(entity.getCreatedBy());
        dto.setUpdatedBy(entity.getUpdatedBy());
        return dto;
    }
}
