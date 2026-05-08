package com.foggy.navigator.business.agent.model.dto;

import com.foggy.navigator.business.agent.model.entity.ClientAppUpstreamUserGrantEntity;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ClientAppUpstreamUserGrantDTO {
    private String grantId;
    private String tenantId;
    private String clientAppId;
    private String upstreamUserId;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static ClientAppUpstreamUserGrantDTO fromEntity(ClientAppUpstreamUserGrantEntity entity) {
        if (entity == null) {
            return null;
        }
        ClientAppUpstreamUserGrantDTO dto = new ClientAppUpstreamUserGrantDTO();
        dto.setGrantId(entity.getGrantId());
        dto.setTenantId(entity.getTenantId());
        dto.setClientAppId(entity.getClientAppId());
        dto.setUpstreamUserId(entity.getUpstreamUserId());
        dto.setStatus(entity.getStatus());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
    }
}
