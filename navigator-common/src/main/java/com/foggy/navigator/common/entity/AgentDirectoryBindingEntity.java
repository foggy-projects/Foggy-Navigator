package com.foggy.navigator.common.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 目录授权绑定
 * Agent 与工作目录的多对多关系。一个 Agent 可被授权在多个目录工作。
 */
@Data
@Entity
@Table(name = "coding_agent_directories", indexes = {
    @Index(name = "idx_cad_agent_id", columnList = "agentId"),
    @Index(name = "idx_cad_directory_id", columnList = "directoryId")
})
public class AgentDirectoryBindingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** FK -> CodingAgentEntity.agentId */
    @Column(length = 64, nullable = false)
    private String agentId;

    /** FK -> WorkingDirectoryEntity.directoryId */
    @Column(length = 64, nullable = false)
    private String directoryId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
