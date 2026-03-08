package com.foggy.navigator.claude.worker.model.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 跨项目任务 DTO
 */
@Data
@Builder
public class CrossProjectTaskDTO {
    private String contextId;
    private String title;
    private String description;
    private String status;
    private Integer currentPhaseIndex;
    private Integer totalPhases;
    private String executionMode;
    private BigDecimal totalCostUsd;
    private String initialSessionId;
    private String initialDirectoryId;
    private List<CrossProjectPhaseDTO> phases;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;
}
