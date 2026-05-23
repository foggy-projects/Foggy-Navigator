package com.foggy.navigator.business.agent.model.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class UpstreamBootstrapRequestDTO {
    private String requestId;
    private String requestCodeSuffix;
    private String upstreamSystemId;
    private String requestedTenantId;
    private Boolean multiTenant;
    private String reason;
    private String applicantLabel;
    private String status;
    private String deniedReason;
    private List<String> authorizedTenantIds;
    private String authorizedClientAppNamespace;
    private List<String> scopes;
    private LocalDateTime requestExpiresAt;
    private LocalDateTime approvedAt;
    private LocalDateTime deniedAt;
    private LocalDateTime claimExpiresAt;
    private LocalDateTime adminCredentialExpiresAt;
    private LocalDateTime consumedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
