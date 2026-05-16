package com.foggy.navigator.business.agent.model.dto;

import com.foggy.navigator.business.agent.model.entity.ClientAppUpstreamRouteEntity;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ClientAppUpstreamRouteDTO {
    private Long id;
    private String tenantId;
    private String clientAppId;
    private String upstreamRef;
    private String baseUrl;
    private String userTokenHeader;
    private String status;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static ClientAppUpstreamRouteDTO fromEntity(ClientAppUpstreamRouteEntity entity) {
        if (entity == null) {
            return null;
        }
        ClientAppUpstreamRouteDTO dto = new ClientAppUpstreamRouteDTO();
        dto.setId(entity.getId());
        dto.setTenantId(entity.getTenantId());
        dto.setClientAppId(entity.getClientAppId());
        dto.setUpstreamRef(entity.getUpstreamRef());
        dto.setBaseUrl(entity.getBaseUrl());
        dto.setUserTokenHeader(entity.getUserTokenHeader());
        dto.setStatus(entity.getStatus());
        dto.setDescription(entity.getDescription());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
    }
}
