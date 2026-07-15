package com.foggy.navigator.business.agent.model.dto;

import com.foggy.navigator.common.enums.ResourceOwnerType;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class BizWorkerPrincipal {
    String workerId;
    ResourceOwnerType ownerType;
    String ownerId;
    String workerBackend;
    Integer credentialVersion;
}
