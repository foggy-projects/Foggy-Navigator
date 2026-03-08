package com.foggy.navigator.task.assistant.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 任务助手配置实体
 */
@Data
@Entity
@Table(name = "task_assistant_configs", indexes = {
    @Index(name = "idx_tac_user_id", columnList = "userId")
})
public class TaskAssistantConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 64, nullable = false, unique = true)
    private String userId;

    @Column(nullable = false)
    private Boolean enabled = false;

    @Column(length = 64)
    private String foggySessionId;

    @Column(length = 64)
    private String workerId;

    @Column(length = 64)
    private String directoryId;

    @Column(length = 128)
    private String claudeSessionId;

    @Column(length = 512)
    private String cwd;

    /** 平台 LLM 模型配置 ID（用于绑定工作目录 auth + 解析 API Key） */
    @Column(length = 64)
    private String modelConfigId;

    /** AI 模型名称，如 claude-sonnet-4-20250514 */
    @Column(length = 128)
    private String model;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (updatedAt == null) {
            updatedAt = LocalDateTime.now();
        }
        if (enabled == null) {
            enabled = false;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
