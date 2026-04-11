package com.foggy.navigator.common.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "session_relations", indexes = {
    @Index(name = "idx_sr_user_id", columnList = "userId"),
    @Index(name = "idx_sr_source_session_id", columnList = "sourceSessionId"),
    @Index(name = "idx_sr_target_session_id", columnList = "targetSessionId"),
    @Index(name = "idx_sr_source_message_id", columnList = "sourceMessageId"),
    @Index(name = "idx_sr_relation_type", columnList = "relationType")
})
public class SessionRelationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 64, nullable = false)
    private String userId;

    @Column(length = 64)
    private String tenantId;

    @Column(length = 32, nullable = false)
    private String relationType;

    @Column(length = 32, nullable = false)
    private String targetMode;

    @Column(length = 64, nullable = false)
    private String sourceSessionId;

    @Column(length = 64)
    private String sourceMessageId;

    @Column(length = 64, nullable = false)
    private String targetSessionId;

    @Column(length = 64)
    private String sourceWorkerId;

    @Column(length = 64)
    private String sourceDirectoryId;

    @Column(length = 64)
    private String sourceMilestoneId;

    @Column(length = 64)
    private String targetWorkerId;

    @Column(length = 64)
    private String targetDirectoryId;

    @Column(length = 64)
    private String targetMilestoneId;

    @Column(length = 32)
    private String targetProviderType;

    @Column(length = 64)
    private String targetModelConfigId;

    @Column(columnDefinition = "TEXT")
    private String metadataJson;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
