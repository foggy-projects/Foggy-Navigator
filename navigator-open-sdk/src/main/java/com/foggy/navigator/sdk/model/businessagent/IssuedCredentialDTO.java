package com.foggy.navigator.sdk.model.businessagent;

import java.time.LocalDateTime;
import java.util.Set;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;


@JsonIgnoreProperties(ignoreUnknown = true)
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
    public String getControlApiKey() { return controlApiKey; }
    public void setControlApiKey(String controlApiKey) { this.controlApiKey = controlApiKey; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public Set<String> getScopes() { return scopes; }
    public void setScopes(Set<String> scopes) { this.scopes = scopes; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
}
