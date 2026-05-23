package com.foggy.navigator.business.agent.model.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class UpstreamTenantClientAppProvisioningDTO {
    private String navigatorTenantId;
    private String clientAppId;
    private String clientAppName;
    private String capabilityDomain;
    private String clientAppKey;
    private String clientAppSecret;
    private String controlApiKey;
    private String rootAgentId;
    private String modelConfigId;
    private String skillId;
    private String workerPoolId;
    private String bindingVersion;
    private String status;
    private String errorCode;
    private String message;
    private boolean credentialsReplayable;
    private boolean created;
    private boolean rotated;
    private List<String> blockers = new ArrayList<>();
}
