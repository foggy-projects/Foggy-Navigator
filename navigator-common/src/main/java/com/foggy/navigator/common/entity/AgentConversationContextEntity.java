package com.foggy.navigator.common.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * contextId ↔ agentSessionRef 映射 — 支持 A2A 多轮有状态会话
 */
@Data
@Entity
@Table(name = "agent_conversation_contexts", indexes = {
    @Index(name = "idx_acc_user_agent", columnList = "userId, targetAgentId")
})
public class AgentConversationContextEntity {

    @Id
    @Column(length = 64)
    private String contextId;

    /** Agent 类型标识，如 "claude-worker" */
    @Column(length = 32, nullable = false)
    private String agentType;

    /** Agent 侧的会话标识（claudeSessionId 等） */
    @Column(length = 256)
    private String agentSessionRef;

    @Column(length = 64, nullable = false)
    private String userId;

    @Column(length = 64, nullable = false)
    private String targetAgentId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime lastAccessedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (lastAccessedAt == null) lastAccessedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        lastAccessedAt = LocalDateTime.now();
    }
}
