package com.foggy.navigator.sdk.model.businessagent;

import java.time.LocalDateTime;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;


@JsonIgnoreProperties(ignoreUnknown = true)
public class IssuedCredentialDTO {
    private String credentialId;
    private String clientAppId;
    private String appKey;
    private String secret;
    private String token;
    private String tenantId;
    private LocalDateTime expiresAt;

    public String getCredentialId() { return credentialId; }
    public void setCredentialId(String credentialId) { this.credentialId = credentialId; }
    public String getClientAppId() { return clientAppId; }
    public void setClientAppId(String clientAppId) { this.clientAppId = clientAppId; }
    public String getAppKey() { return appKey; }
    public void setAppKey(String appKey) { this.appKey = appKey; }
    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
}
