package com.foggy.navigator.business.agent.model.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Non-secret diagnostics for an explicitly targeted upstream-admin ClientApp operation.
 */
@Data
@Builder
public class UpstreamAdminClientAppScopeDTO {
    private String credentialLane;
    private String principalType;
    private String upstreamSystemId;
    private String tenantId;
    private String clientAppId;
    private String clientAppNamespace;
    private String targetOwnerType;
    private String targetOwnerId;
    private List<String> authorizationChecks;
}
