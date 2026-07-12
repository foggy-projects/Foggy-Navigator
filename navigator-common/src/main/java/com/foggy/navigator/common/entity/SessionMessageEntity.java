package com.foggy.navigator.common.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话消息 JPA Entity
 * 对齐 agent-framework 的 Message POJO
 */
@Data
@Entity
@Table(name = "session_messages", indexes = {
    @Index(name = "idx_msg_session_id", columnList = "sessionId"),
    @Index(name = "idx_msg_created_at", columnList = "createdAt"),
    @Index(name = "idx_msg_task_id", columnList = "taskId")
})
public class SessionMessageEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(length = 64, nullable = false)
    private String sessionId;

    /** 关联的平台任务 ID（可空，历史数据可能无此字段） */
    @Column(length = 64)
    private String taskId;

    @Column(length = 32, nullable = false)
    private String role;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String content;

    /**
     * JSON序列化的 Map<String, Object>
     * 使用 MEDIUMTEXT 而非 JSON 列以兼容 H2 测试，同时承载最终回复的
     * 结构化 metadata，不对大于 TEXT 上限的 UTF-8 内容静默截断。
     */
    @Column(columnDefinition = "MEDIUMTEXT")
    private String metadata;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
