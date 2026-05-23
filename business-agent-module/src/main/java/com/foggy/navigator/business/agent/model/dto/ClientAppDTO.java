package com.foggy.navigator.business.agent.model.dto;

import com.foggy.navigator.business.agent.model.entity.ClientAppEntity;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ClientAppDTO {
    private String clientAppId;
    private String tenantId;
    private String name;
    private String description;
    private String ownerUserId;
    private String capabilityDomain;
    private String upstreamSystemId;
    private String upstreamClientAppNamespace;
    private String upstreamRef;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static ClientAppDTO fromEntity(ClientAppEntity entity) {
        ClientAppDTO dto = new ClientAppDTO();
        dto.setClientAppId(entity.getClientAppId());
        dto.setTenantId(entity.getTenantId());
        dto.setName(entity.getName());
        dto.setDescription(entity.getDescription());
        dto.setOwnerUserId(entity.getOwnerUserId());
        dto.setCapabilityDomain(entity.getCapabilityDomain());
        dto.setUpstreamSystemId(entity.getUpstreamSystemId());
        dto.setUpstreamClientAppNamespace(entity.getUpstreamClientAppNamespace());
        dto.setUpstreamRef(entity.getUpstreamRef());
        dto.setStatus(entity.getStatus());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
    }
}
