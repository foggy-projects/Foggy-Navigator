package com.foggy.navigator.business.agent.model.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class IssuedCredentialDTO {
    private String credentialId;
    private String clientAppId;
    private String appKey;
    private String secret;
    private String token;
    private String tenantId;
    private LocalDateTime expiresAt;
}
