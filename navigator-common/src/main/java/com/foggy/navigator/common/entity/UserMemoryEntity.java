package com.foggy.navigator.common.entity;

import com.foggy.navigator.common.enums.UserMemoryCategory;
import com.foggy.navigator.common.enums.UserMemorySource;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户记忆实体
 */
@Data
@Entity
@Table(name = "user_memories", indexes = {
    @Index(name = "idx_um_user_id", columnList = "userId"),
    @Index(name = "idx_um_user_category", columnList = "userId, category")
})
public class UserMemoryEntity {

    @Id
    @Column(length = 64)
    private String id;

    /**
     * 用户ID
     */
    @Column(length = 64, nullable = false)
    private String userId;

    /**
     * 租户ID
     */
    @Column(length = 64, nullable = false)
    private String tenantId;

    /**
     * 记忆类别
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private UserMemoryCategory category;

    /**
     * 记忆内容
     */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    /**
     * 来源（自动/手动）
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private UserMemorySource source;

    /**
     * 创建时间
     */
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
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
