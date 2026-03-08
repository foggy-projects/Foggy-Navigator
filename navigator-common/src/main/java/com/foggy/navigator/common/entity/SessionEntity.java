package com.foggy.navigator.common.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话 JPA Entity
 * 对齐 agent-framework 的 Session POJO
 */
@Data
@Entity
@Table(name = "sessions", indexes = {
    @Index(name = "idx_session_user_id", columnList = "userId"),
    @Index(name = "idx_session_tenant_id", columnList = "tenantId"),
    @Index(name = "idx_session_agent_id", columnList = "agentId"),
    @Index(name = "idx_session_status", columnList = "status"),
    @Index(name = "idx_session_parent_id", columnList = "parentSessionId")
})
public class SessionEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(length = 64)
    private String userId;

    @Column(length = 64)
    private String tenantId;

    @Column(length = 64)
    private String agentId;

    @Column(length = 64)
    private String parentSessionId;

    @Column(length = 256)
    private String title;

    @Column(length = 32, nullable = false)
    private String status;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 参与本会话的 Agent ID 列表，JSON 数组 ["agentId1","agentId2"] */
    @Column(columnDefinition = "TEXT")
    private String participatingAgentIds;

    /** AI 生成的会话摘要 */
    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (status == null) {
            status = "ACTIVE";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
