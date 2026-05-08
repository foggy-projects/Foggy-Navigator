package com.foggy.navigator.business.agent.model.dto;

import com.foggy.navigator.business.agent.model.entity.ClientAppModelConfigGrantEntity;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ClientAppModelConfigGrantDTO {
    private Long id;
    private String clientAppId;
    private String tenantId;
    private String modelConfigId;
    private String modelConfigName;
    private String workerBackend;
    private String status;
    private Boolean isDefault;
    private String grantScope;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static ClientAppModelConfigGrantDTO fromEntity(ClientAppModelConfigGrantEntity entity) {
        ClientAppModelConfigGrantDTO dto = new ClientAppModelConfigGrantDTO();
        dto.setId(entity.getId());
        dto.setClientAppId(entity.getClientAppId());
        dto.setTenantId(entity.getTenantId());
        dto.setModelConfigId(entity.getModelConfigId());
        dto.setStatus(entity.getStatus());
        dto.setIsDefault(entity.getIsDefault());
        dto.setGrantScope(entity.getGrantScope());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
    }
}
