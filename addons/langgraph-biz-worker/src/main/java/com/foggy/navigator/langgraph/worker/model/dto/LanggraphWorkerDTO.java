package com.foggy.navigator.langgraph.worker.model.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class LanggraphWorkerDTO {
    private String workerId;
    private String name;
    private String baseUrl;
    private String authMode;
    private String status;
    private String hostname;
    private String workerVersion;
    private LocalDateTime lastHeartbeat;
    private LocalDateTime createdAt;
    private String providerExt;
}
