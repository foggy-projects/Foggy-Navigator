package com.foggy.navigator.business.agent.model.dto;

import com.foggy.navigator.business.agent.model.entity.BizWorkerIdentityEntity;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import lombok.Data;

@Data
public class BizWorkerIdentityDTO {
    private String workerId;
    private ResourceOwnerType ownerType;
    private String ownerId;
    private String workerBackend;
    private String baseUrl;
    private String version;
    private String status;
    private String healthStatus;
    private String capabilitiesJson;
    private String labelsJson;

    public static BizWorkerIdentityDTO fromEntity(BizWorkerIdentityEntity entity) {
        BizWorkerIdentityDTO dto = new BizWorkerIdentityDTO();
        dto.setWorkerId(entity.getWorkerId());
        dto.setOwnerType(entity.getOwnerType());
        dto.setOwnerId(entity.getOwnerId());
        dto.setWorkerBackend(entity.getWorkerBackend());
        dto.setBaseUrl(entity.getBaseUrl());
        dto.setVersion(entity.getVersion());
        dto.setStatus(entity.getStatus());
        dto.setHealthStatus(entity.getHealthStatus());
        dto.setCapabilitiesJson(entity.getCapabilitiesJson());
        dto.setLabelsJson(entity.getLabelsJson());
        return dto;
    }
}
