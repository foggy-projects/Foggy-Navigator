package com.foggy.navigator.business.agent.model.dto;

import com.foggy.navigator.business.agent.model.entity.ClientAppFunctionGrantEntity;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ClientAppFunctionGrantDTO {
    private String grantId;
    private String tenantId;
    private String clientAppId;
    private String functionId;
    private String version;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static ClientAppFunctionGrantDTO fromEntity(ClientAppFunctionGrantEntity entity) {
        if (entity == null) {
            return null;
        }
        ClientAppFunctionGrantDTO dto = new ClientAppFunctionGrantDTO();
        dto.setGrantId(entity.getGrantId());
        dto.setTenantId(entity.getTenantId());
        dto.setClientAppId(entity.getClientAppId());
        dto.setFunctionId(entity.getFunctionId());
        dto.setVersion(entity.getVersion());
        dto.setStatus(entity.getStatus());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
    }
}
