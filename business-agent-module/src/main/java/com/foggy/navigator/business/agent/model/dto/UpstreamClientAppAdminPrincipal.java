package com.foggy.navigator.business.agent.model.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Set;

@Data
@Builder
public class UpstreamClientAppAdminPrincipal {
    private String credentialId;
    private String principalId;
    private String upstreamSystemId;
    private String authorizedClientAppNamespace;
    private Set<String> authorizedTenantIds;
    private Set<String> scopes;
}
