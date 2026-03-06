package com.foggy.navigator.claude.worker.model.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话级配置（置顶、自定义标题、Auth 绑定）
 */
@Data
@Entity
@Table(name = "claude_conversation_configs", indexes = {
    @Index(name = "idx_ccc_user_worker", columnList = "userId, workerId"),
    @Index(name = "idx_ccc_session_id", columnList = "sessionId")
})
public class ConversationConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 64, nullable = false, unique = true)
    private String sessionId;

    @Column(length = 64, nullable = false)
    private String workerId;

    @Column(length = 64, nullable = false)
    private String userId;

    @Column(nullable = false)
    private Boolean pinned = false;

    private LocalDateTime pinnedAt;

    @Column(length = 255)
    private String customTitle;

    @Column(length = 32)
    private String authMode;

    @Column(columnDefinition = "TEXT")
    private String authToken;

    @Column(length = 512)
    private String baseUrl;

    private LocalDateTime authBoundAt;

    /** JSON array of tags: ["主任务","bugfix"] */
    @Column(columnDefinition = "TEXT")
    private String tags;

    /** 交互状态：PROCESSING / AWAITING_REPLY / ARCHIVED */
    @Column(length = 32)
    private String interactionState;

    /** 会话绑定的 Agent Teams 配置 ID（创建后不可变更） */
    @Column(length = 64)
    private String agentTeamsConfigId;

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
        if (pinned == null) {
            pinned = false;
        }
        if (interactionState == null) {
            interactionState = "PROCESSING";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
