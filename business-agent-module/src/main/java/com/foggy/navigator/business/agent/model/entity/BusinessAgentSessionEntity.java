package com.foggy.navigator.business.agent.model.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "business_agent_session",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_biz_agent_session_context",
                        columnNames = {"tenantId", "clientAppId", "upstreamUserId", "contextId"}),
                @UniqueConstraint(name = "uk_biz_agent_session_session",
                        columnNames = {"tenantId", "clientAppId", "upstreamUserId", "sessionId"})
        },
        indexes = {
                @Index(name = "idx_biz_agent_session_tenant_app_user",
                        columnList = "tenantId,clientAppId,upstreamUserId"),
                @Index(name = "idx_biz_agent_session_last_accessed",
                        columnList = "lastAccessedAt"),
                @Index(name = "idx_biz_agent_session_skill",
                        columnList = "skillId")
        })
public class BusinessAgentSessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 64, nullable = false)
    private String tenantId;

    @Column(length = 64, nullable = false)
    private String clientAppId;

    @Column(length = 128, nullable = false)
    private String upstreamUserId;

    @Column(length = 128)
    private String accountId;

    @Column(length = 64, nullable = false)
    private String contextId;

    @Column(length = 64, nullable = false)
    private String sessionId;

    @Column(length = 128)
    private String skillId;

    @Column(length = 128)
    private String agentId;

    @Column(length = 64)
    private String latestTaskId;

    @Column(length = 32, nullable = false)
    private String status;

    @Column(columnDefinition = "TEXT")
    private String clientContextJson;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Column(nullable = false)
    private LocalDateTime lastAccessedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (lastAccessedAt == null) {
            lastAccessedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
