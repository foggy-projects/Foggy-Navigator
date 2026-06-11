package com.foggy.navigator.sdk.model.businessagent;

import java.time.LocalDateTime;
import java.util.List;

public class RotateUpstreamAdminCredentialForm {
    private LocalDateTime credentialExpiresAt;
    private List<String> scopes;

    public LocalDateTime getCredentialExpiresAt() { return credentialExpiresAt; }
    public void setCredentialExpiresAt(LocalDateTime credentialExpiresAt) { this.credentialExpiresAt = credentialExpiresAt; }

    public List<String> getScopes() { return scopes; }
    public void setScopes(List<String> scopes) { this.scopes = scopes; }
}
