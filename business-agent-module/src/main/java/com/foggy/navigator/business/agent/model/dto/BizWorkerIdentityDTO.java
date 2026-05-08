package com.foggy.navigator.business.agent.model.dto;

import com.foggy.navigator.business.agent.model.entity.BizWorkerIdentityEntity;
import lombok.Data;

@Data
public class BizWorkerIdentityDTO {
    private String workerId;
    private String workerBackend;
    private String baseUrl;
    private String version;
    private String status;
    private String healthStatus;

    public static BizWorkerIdentityDTO fromEntity(BizWorkerIdentityEntity entity) {
        BizWorkerIdentityDTO dto = new BizWorkerIdentityDTO();
        dto.setWorkerId(entity.getWorkerId());
        dto.setWorkerBackend(entity.getWorkerBackend());
        dto.setBaseUrl(entity.getBaseUrl());
        dto.setVersion(entity.getVersion());
        dto.setStatus(entity.getStatus());
        dto.setHealthStatus(entity.getHealthStatus());
        return dto;
    }
}
