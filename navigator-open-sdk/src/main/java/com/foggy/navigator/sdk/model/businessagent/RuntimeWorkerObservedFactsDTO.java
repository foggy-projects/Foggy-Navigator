package com.foggy.navigator.sdk.model.businessagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.OffsetDateTime;

/** Content-free Worker/provider observations used by completion readiness. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RuntimeWorkerObservedFactsDTO {
    private Boolean workerReachable;
    private OffsetDateTime workerObservedAt;
    private Boolean workerTaskKnown;
    private String workerTaskState = "UNKNOWN";
    private Boolean providerProcessPresent;
    private String providerProcessState = "UNKNOWN";
    private Boolean providerActiveTaskPresent;
    private Boolean providerTaskTerminal;
    private String providerTerminalStatus;
    private OffsetDateTime lastHeartbeatAt;
    private OffsetDateTime lastProgressAt;
    private OffsetDateTime processExitedAt;

    public Boolean getWorkerReachable() { return workerReachable; }
    public void setWorkerReachable(Boolean value) { workerReachable = value; }
    public OffsetDateTime getWorkerObservedAt() { return workerObservedAt; }
    public void setWorkerObservedAt(OffsetDateTime value) { workerObservedAt = value; }
    public Boolean getWorkerTaskKnown() { return workerTaskKnown; }
    public void setWorkerTaskKnown(Boolean value) { workerTaskKnown = value; }
    public String getWorkerTaskState() { return normalized(workerTaskState); }
    public void setWorkerTaskState(String value) { workerTaskState = normalized(value); }
    public Boolean getProviderProcessPresent() { return providerProcessPresent; }
    public void setProviderProcessPresent(Boolean value) { providerProcessPresent = value; }
    public String getProviderProcessState() { return normalized(providerProcessState); }
    public void setProviderProcessState(String value) { providerProcessState = normalized(value); }
    public Boolean getProviderActiveTaskPresent() { return providerActiveTaskPresent; }
    public void setProviderActiveTaskPresent(Boolean value) { providerActiveTaskPresent = value; }
    public Boolean getProviderTaskTerminal() { return providerTaskTerminal; }
    public void setProviderTaskTerminal(Boolean value) { providerTaskTerminal = value; }
    public String getProviderTerminalStatus() { return providerTerminalStatus; }
    public void setProviderTerminalStatus(String value) { providerTerminalStatus = value; }
    public OffsetDateTime getLastHeartbeatAt() { return lastHeartbeatAt; }
    public void setLastHeartbeatAt(OffsetDateTime value) { lastHeartbeatAt = value; }
    public OffsetDateTime getLastProgressAt() { return lastProgressAt; }
    public void setLastProgressAt(OffsetDateTime value) { lastProgressAt = value; }
    public OffsetDateTime getProcessExitedAt() { return processExitedAt; }
    public void setProcessExitedAt(OffsetDateTime value) { processExitedAt = value; }

    private String normalized(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value;
    }
}
