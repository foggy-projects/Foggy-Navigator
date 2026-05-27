package com.foggy.navigator.business.agent.model.dto;

import lombok.Data;

@Data
public class PhysicalWorkerDiagnosticDTO {
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
}
