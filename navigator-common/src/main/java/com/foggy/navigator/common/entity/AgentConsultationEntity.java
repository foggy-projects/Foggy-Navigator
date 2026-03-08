package com.foggy.navigator.common.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * @agent 咨询记录 — 记录 TaskPane 中用户向其他 Agent 提问的历史
 */
@Data
@Entity
@Table(name = "agent_consultations", indexes = {
    @Index(name = "idx_consult_session", columnList = "sessionId"),
    @Index(name = "idx_consult_user", columnList = "userId"),
    @Index(name = "idx_consult_agent", columnList = "targetAgentId")
})
public class AgentConsultationEntity {

    @Id
    @Column(length = 64)
    private String id;

    /** FK → sessions（Claude Worker 会话） */
    @Column(length = 64, nullable = false)
    private String sessionId;

    @Column(length = 64, nullable = false)
    private String userId;

    /** 被 @mention 的 Agent ID */
    @Column(length = 64, nullable = false)
    private String targetAgentId;

    /** 冗余存储 Agent 名称 */
    @Column(length = 128)
    private String targetAgentName;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String question;

    @Column(columnDefinition = "TEXT")
    private String answer;

    /** COMPLETED / FAILED */
    @Column(length = 32, nullable = false)
    private String status;

    private Long durationMs;

    /** 关联多轮对话上下文 */
    @Column(length = 64)
    private String contextId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (status == null) {
            status = "COMPLETED";
        }
    }
}
