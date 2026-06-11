package com.foggy.navigator.business.agent.model.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class UpstreamTenantClientAppProvisioningDTO {
    private String navigatorTenantId;
    private String targetNavigatorTenantId;
    private String clientAppId;
    private String clientAppName;
    private String capabilityDomain;
    private String clientAppCapabilityDomain;
    private String upstreamSystemId;
    private String sourceTenantId;
    private String upstreamRef;
    private String upstreamNamespace;
    private String clientAppKey;
    private String clientAppSecret;
    private String controlApiKey;
    private String agentCode;
    private String rootAgentId;
    private String modelConfigId;
    private String skillId;
    private String workerPoolId;
    private String workerBackend;
    private String physicalWorkerId;
    private String directoryId;
    private String bizWorkerBaseUrl;
    private String bindingVersion;
    private String status;
    private String errorCode;
    private String message;
    private String remediationHint;
    private boolean credentialsReplayable;
    private boolean created;
    private boolean rotated;
    private boolean activationReady;
    private List<String> blockers = new ArrayList<>();
    private List<String> missingFields = new ArrayList<>();
    private List<String> requiredScopes = new ArrayList<>();
    private List<String> actualScopes = new ArrayList<>();
    private List<String> authorizedTenantIds = new ArrayList<>();
}
