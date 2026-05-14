package com.foggy.navigator.business.agent.model.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Set;

@Data
public class IssuedCredentialDTO {
    private String credentialId;
    private String clientAppId;
    private String appKey;
    private String secret;
    private String token;
    private String controlApiKey;
    private String tenantId;
    private Set<String> scopes;
    private LocalDateTime expiresAt;
}
