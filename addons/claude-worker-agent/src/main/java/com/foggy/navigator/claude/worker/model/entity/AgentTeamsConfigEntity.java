package com.foggy.navigator.claude.worker.model.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent Teams 命名配置（一个工作目录可有多条，至多一条为默认）
 */
@Data
@Entity
@Table(name = "claude_agent_teams_configs", indexes = {
    @Index(name = "idx_atc_directory_id", columnList = "directoryId"),
    @Index(name = "idx_atc_user_id", columnList = "userId")
})
public class AgentTeamsConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 64, nullable = false, unique = true)
    private String configId;

    @Column(length = 64, nullable = false)
    private String directoryId;

    @Column(length = 64, nullable = false)
    private String userId;

    @Column(length = 128, nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String config;

    @Column(nullable = false)
    private Boolean isDefault = false;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
