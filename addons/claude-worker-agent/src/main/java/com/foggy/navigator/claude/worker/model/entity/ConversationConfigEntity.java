package com.foggy.navigator.claude.worker.model.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话级配置（置顶、自定义标题、Auth 绑定、模型映射）
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

    /**
     * 模型映射配置
     */
    @Column(length = 128)
    private String haikuModelName;

    @Column(length = 128)
    private String sonnetModelName;

    @Column(length = 128)
    private String opusModelName;

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
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
