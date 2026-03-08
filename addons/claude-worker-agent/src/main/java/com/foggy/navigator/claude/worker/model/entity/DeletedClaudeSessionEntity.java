package com.foggy.navigator.claude.worker.model.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 已删除的 Claude 会话记录。
 * 用于防止 syncLocalSessions 重新导入已被用户手动删除的同步会话。
 */
@Data
@Entity
@Table(name = "deleted_claude_sessions", indexes = {
    @Index(name = "idx_dcs_worker_user", columnList = "workerId, userId")
})
public class DeletedClaudeSessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 128, nullable = false)
    private String claudeSessionId;

    @Column(length = 64, nullable = false)
    private String workerId;

    @Column(length = 64, nullable = false)
    private String userId;

    @Column(nullable = false)
    private LocalDateTime deletedAt;

    @PrePersist
    protected void onCreate() {
        if (deletedAt == null) {
            deletedAt = LocalDateTime.now();
        }
    }
}
