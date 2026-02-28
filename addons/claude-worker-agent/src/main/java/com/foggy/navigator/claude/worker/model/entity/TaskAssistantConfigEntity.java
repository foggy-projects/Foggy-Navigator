package com.foggy.navigator.claude.worker.model.entity;

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

    @Column(length = 64, nullable = false)
    private String workerId;

    @Column(length = 64)
    private String directoryId;

    @Column(length = 128)
    private String claudeSessionId;

    @Column(length = 64)
    private String foggySessionId;

    @Column(nullable = false)
    private Boolean enabled = false;

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
