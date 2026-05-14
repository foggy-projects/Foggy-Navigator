package com.foggy.navigator.sdk.model.businessagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDateTime;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class IssueControlCredentialForm {
    private LocalDateTime expiresAt;
    private String description;
    private String effectiveUserId;
    private List<String> scopes;

    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getEffectiveUserId() { return effectiveUserId; }
    public void setEffectiveUserId(String effectiveUserId) { this.effectiveUserId = effectiveUserId; }
    public List<String> getScopes() { return scopes; }
    public void setScopes(List<String> scopes) { this.scopes = scopes; }
}
