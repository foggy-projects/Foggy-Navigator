package com.foggy.navigator.codex.worker.model.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Codex Worker 信息 DTO
 */
@Data
@Builder
public class CodexWorkerDTO {
    private String workerId;
    private String name;
    private String baseUrl;
    private String status;
    private String hostname;
    private String workerVersion;
    private LocalDateTime lastHeartbeat;
    private LocalDateTime createdAt;
}
