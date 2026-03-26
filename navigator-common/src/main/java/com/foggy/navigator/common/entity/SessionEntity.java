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
    @Index(name = "idx_session_parent_id", columnList = "parentSessionId"),
    @Index(name = "idx_session_interaction_state", columnList = "interactionState"),
    @Index(name = "idx_session_current_worker_id", columnList = "currentWorkerId"),
    @Index(name = "idx_session_last_activity_at", columnList = "lastActivityAt")
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

    /** Agent Provider 类型，如 "claude-worker" / "codex-worker" */
    @Column(length = 32)
    private String providerType;

    /** 绑定来源: EXPLICIT_AGENT / LEGACY_MODEL_CONFIG / RESTORED */
    @Column(length = 32)
    private String bindingSource;

    @Column(length = 64)
    private String parentSessionId;

    @Column(length = 256)
    private String title;

    @Column(length = 32, nullable = false)
    private String status;

    @Column(length = 32)
    private String interactionState;

    @Column(nullable = false)
    private Boolean pinned = false;

    private LocalDateTime pinnedAt;

    @Column(columnDefinition = "TEXT")
    private String tagsJson;

    @Column(length = 32)
    private String authMode;

    private LocalDateTime authBoundAt;

    @Column(length = 64)
    private String authModelConfigId;

    @Column(length = 512)
    private String authBaseUrl;

    @Column(columnDefinition = "TEXT")
    private String authTokenCiphertext;

    @Column(length = 64)
    private String currentWorkerId;

    @Column(length = 64)
    private String currentDirectoryId;

    @Column(length = 64)
    private String latestTaskId;

    @Column(length = 128)
    private String latestModel;

    private LocalDateTime lastActivityAt;

    @Column(columnDefinition = "TEXT")
    private String providerStateJson;

    private LocalDateTime deletedAt;

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
        if (interactionState == null) {
            interactionState = "PROCESSING";
        }
        if (pinned == null) {
            pinned = false;
        }
        if (lastActivityAt == null) {
            lastActivityAt = updatedAt;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
        if (lastActivityAt == null) {
            lastActivityAt = updatedAt;
        }
    }
}
