package com.foggy.navigator.business.agent.model.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class UpstreamAdminCredentialDTO {
    private String credentialId;
    private String principalId;
    private String credentialKeyPrefix;
    private String credentialKeySuffix;
    private String upstreamSystemId;
    private List<String> authorizedTenantIds;
    private String authorizedClientAppNamespace;
    private List<String> scopes;
    private String status;
    private LocalDateTime expiresAt;
    private LocalDateTime revokedAt;
    private LocalDateTime lastUsedAt;
    private String sourceRequestId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
