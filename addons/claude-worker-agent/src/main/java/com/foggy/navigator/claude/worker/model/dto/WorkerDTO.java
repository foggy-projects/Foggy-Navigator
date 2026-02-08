package com.foggy.navigator.claude.worker.model.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Worker 信息 DTO
 */
@Data
@Builder
public class WorkerDTO {
    private String workerId;
    private String name;
    private String baseUrl;
    private String authMode;
    private String status;
    private String hostname;
    private String workerVersion;
    private LocalDateTime lastHeartbeat;
    private LocalDateTime createdAt;
}
