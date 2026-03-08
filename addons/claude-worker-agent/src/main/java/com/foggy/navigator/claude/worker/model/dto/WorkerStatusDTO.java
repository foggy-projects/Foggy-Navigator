package com.foggy.navigator.claude.worker.model.dto;

import lombok.Builder;
import lombok.Data;

/**
 * Worker 健康状态 DTO
 */
@Data
@Builder
public class WorkerStatusDTO {
    private String workerId;
    private String status;
    private String hostname;
    private String workerVersion;
    private int activeSessions;
    private boolean claudeCliAvailable;
}
