package com.foggy.navigator.business.agent.model.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class UpstreamAdminCredentialClaimDTO {
    private String credentialId;
    private String naviAdminApiKey;
    private String upstreamSystemId;
    private List<String> authorizedTenantIds;
    private String authorizedClientAppNamespace;
    private List<String> scopes;
    private LocalDateTime expiresAt;
}
