package com.foggy.navigator.common.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Agent 配置持久化实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "agent_config", indexes = {
        @Index(name = "idx_agent_config_name", columnList = "name"),
        @Index(name = "idx_agent_config_status", columnList = "status")
})
public class AgentConfigEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 32)
    private String type;

    @Column(length = 512)
    private String description;

    @Column(columnDefinition = "TEXT")
    private String configJson;

    @Column(length = 32)
    @Builder.Default
    private String status = "ACTIVE";

    private LocalDateTime registeredAt;

    private LocalDateTime lastActiveAt;

    @PrePersist
    protected void onCreate() {
        if (registeredAt == null) {
            registeredAt = LocalDateTime.now();
        }
        lastActiveAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        lastActiveAt = LocalDateTime.now();
    }
}
