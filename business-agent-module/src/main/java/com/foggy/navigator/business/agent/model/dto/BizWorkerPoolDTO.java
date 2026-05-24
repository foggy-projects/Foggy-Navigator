package com.foggy.navigator.business.agent.model.dto;

import com.foggy.navigator.business.agent.model.entity.BizWorkerPoolEntity;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import lombok.Data;

@Data
public class BizWorkerPoolDTO {
    private String poolId;
    private String tenantId;
    private ResourceOwnerType ownerType;
    private String ownerId;
    private String name;
    private String workerBackend;
    private String routingPolicy;
    private String status;
    private String healthStatus;
    private String capabilitiesJson;
    private String labelsJson;

    public static BizWorkerPoolDTO fromEntity(BizWorkerPoolEntity entity) {
        BizWorkerPoolDTO dto = new BizWorkerPoolDTO();
        dto.setPoolId(entity.getPoolId());
        dto.setTenantId(entity.getTenantId());
        dto.setOwnerType(entity.getOwnerType());
        dto.setOwnerId(entity.getOwnerId());
        dto.setName(entity.getName());
        dto.setWorkerBackend(entity.getWorkerBackend());
        dto.setRoutingPolicy(entity.getRoutingPolicy());
        dto.setStatus(entity.getStatus());
        dto.setHealthStatus(entity.getHealthStatus());
        dto.setCapabilitiesJson(entity.getCapabilitiesJson());
        dto.setLabelsJson(entity.getLabelsJson());
        return dto;
    }
}
