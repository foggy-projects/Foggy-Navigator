package com.foggy.navigator.claude.worker.model.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 跨项目任务阶段 DTO
 */
@Data
@Builder
public class CrossProjectPhaseDTO {
    private String phaseId;
    private Integer phaseIndex;
    private String phaseName;
    private String prompt;
    private String agentId;
    private String directoryId;
    private String workerId;
    private String status;
    private String claudeTaskId;
    private String phaseSessionId;
    private String claudeSessionId;
    private String worktreeDirectoryId;
    private String worktreeBranch;
    private String handoffArtifact;
    private String incomingContext;

    // enrichment fields
    private String directoryName;
    private String workerName;
    private String agentName;

    private BigDecimal costUsd;
    private Long durationMs;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
