package com.foggy.navigator.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class PhysicalWorkerDiagnostic {
    private String role;
    private String physicalWorkerId;
    private String workerName;
    private String workerBackend;
    private String baseUrl;
    private String status;
    private String healthStatus;
    private String version;
    private String hostname;
    private String lastHeartbeat;
    private String source;
    private Boolean executionWorker;
    private Boolean directoryWorker;

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getPhysicalWorkerId() { return physicalWorkerId; }
    public void setPhysicalWorkerId(String physicalWorkerId) { this.physicalWorkerId = physicalWorkerId; }
    public String getWorkerName() { return workerName; }
    public void setWorkerName(String workerName) { this.workerName = workerName; }
    public String getWorkerBackend() { return workerBackend; }
    public void setWorkerBackend(String workerBackend) { this.workerBackend = workerBackend; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getHealthStatus() { return healthStatus; }
    public void setHealthStatus(String healthStatus) { this.healthStatus = healthStatus; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getHostname() { return hostname; }
    public void setHostname(String hostname) { this.hostname = hostname; }
    public String getLastHeartbeat() { return lastHeartbeat; }
    public void setLastHeartbeat(String lastHeartbeat) { this.lastHeartbeat = lastHeartbeat; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public Boolean getExecutionWorker() { return executionWorker; }
    public void setExecutionWorker(Boolean executionWorker) { this.executionWorker = executionWorker; }
    public Boolean getDirectoryWorker() { return directoryWorker; }
    public void setDirectoryWorker(Boolean directoryWorker) { this.directoryWorker = directoryWorker; }
}
