package com.foggy.navigator.business.agent.model.form;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class ApproveUpstreamBootstrapRequestForm {
    private List<String> authorizedTenantIds;
    private String authorizedClientAppNamespace;
    private List<String> scopes;
    private Long claimTtlMinutes;
    private LocalDateTime credentialExpiresAt;
}
