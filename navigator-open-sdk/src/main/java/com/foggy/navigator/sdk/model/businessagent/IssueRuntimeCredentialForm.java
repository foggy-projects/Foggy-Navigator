package com.foggy.navigator.sdk.model.businessagent;

import java.time.LocalDateTime;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;


@JsonIgnoreProperties(ignoreUnknown = true)
public class IssueRuntimeCredentialForm {
    private LocalDateTime expiresAt;
    private String description;

    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
