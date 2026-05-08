package com.foggy.navigator.business.agent.model.dto;

import com.foggy.navigator.business.agent.model.entity.BizWorkerPoolEntity;
import lombok.Data;

@Data
public class BizWorkerPoolDTO {
    private String poolId;
    private String tenantId;
    private String name;
    private String workerBackend;
    private String routingPolicy;
    private String status;
    private String healthStatus;

    public static BizWorkerPoolDTO fromEntity(BizWorkerPoolEntity entity) {
        BizWorkerPoolDTO dto = new BizWorkerPoolDTO();
        dto.setPoolId(entity.getPoolId());
        dto.setTenantId(entity.getTenantId());
        dto.setName(entity.getName());
        dto.setWorkerBackend(entity.getWorkerBackend());
        dto.setRoutingPolicy(entity.getRoutingPolicy());
        dto.setStatus(entity.getStatus());
        dto.setHealthStatus(entity.getHealthStatus());
        return dto;
    }
}
