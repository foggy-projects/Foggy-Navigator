package com.foggy.navigator.sdk.model.businessagent;

import java.time.LocalDateTime;

public class RotateUpstreamAdminCredentialForm {
    private LocalDateTime credentialExpiresAt;

    public LocalDateTime getCredentialExpiresAt() { return credentialExpiresAt; }
    public void setCredentialExpiresAt(LocalDateTime credentialExpiresAt) { this.credentialExpiresAt = credentialExpiresAt; }
}
