package com.foggy.navigator.business.agent.model.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ResolvedClientAppCredentialDTO {
    private String credentialId;
    private String tenantId;
    private String clientAppId;
}
