package com.foggy.navigator.sdk.model.businessagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDateTime;

@JsonIgnoreProperties(ignoreUnknown = true)
public class RuntimeTaskAuditStageDTO {
    private String stage;
    private String status;
    private String sanitizedErrorCode;
    private LocalDateTime occurredAt;

    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSanitizedErrorCode() { return sanitizedErrorCode; }
    public void setSanitizedErrorCode(String sanitizedErrorCode) { this.sanitizedErrorCode = sanitizedErrorCode; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(LocalDateTime occurredAt) { this.occurredAt = occurredAt; }
}
