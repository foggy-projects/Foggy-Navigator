package com.foggy.navigator.business.agent.model.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ClientAppRuntimeAccessTokenDTO {
    private String tokenId;
    private String tenantId;
    private String clientAppId;
    private String credentialId;
    private String appKey;
    private String accessToken;
    private String tokenType;
    private Long expiresInSeconds;
    private LocalDateTime expiresAt;
}
