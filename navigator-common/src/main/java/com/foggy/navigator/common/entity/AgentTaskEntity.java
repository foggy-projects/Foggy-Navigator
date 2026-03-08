package com.foggy.navigator.common.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 跨 Agent 统一任务追踪
 */
@Data
@Entity
@Table(name = "agent_tasks", indexes = {
    @Index(name = "idx_agent_task_parent_session", columnList = "parentSessionId"),
    @Index(name = "idx_agent_task_user_id", columnList = "userId"),
    @Index(name = "idx_agent_task_status", columnList = "status"),
    @Index(name = "idx_agent_task_external", columnList = "externalTaskId,taskType")
})
public class AgentTaskEntity {

    @Id
    @Column(length = 64)
    private String taskId;

    @Column(length = 64, nullable = false)
    private String parentSessionId;

    @Column(length = 64, nullable = false)
    private String userId;

    @Column(length = 64, nullable = false)
    private String sourceAgentId;

    @Column(length = 64, nullable = false)
    private String targetAgentId;

    @Column(length = 64)
    private String targetSessionId;

    @Column(length = 32, nullable = false)
    private String taskType;

    @Column(length = 32, nullable = false)
    private String status;

    @Column(columnDefinition = "TEXT")
    private String prompt;

    @Column(columnDefinition = "TEXT")
    private String resultSummary;

    @Column(length = 128)
    private String externalTaskId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime completedAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (status == null) {
            status = "PENDING";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
