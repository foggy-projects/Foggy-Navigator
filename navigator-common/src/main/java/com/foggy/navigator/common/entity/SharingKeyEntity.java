package com.foggy.navigator.common.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 共享密钥实体 — 用于将 Agent 安全共享给外部用户
 * <p>
 * 外部用户通过 Sharing Key 调用 Agent，无需登录。
 * 每个 Key 绑定一个 Agent，支持每日限额、过期时间、自定义系统提示词。
 */
@Data
@Entity
@Table(name = "sharing_keys", indexes = {
    @Index(name = "idx_sk_key", columnList = "sharingKey", unique = true),
    @Index(name = "idx_sk_owner", columnList = "ownerUserId"),
    @Index(name = "idx_sk_agent", columnList = "agentId")
})
public class SharingKeyEntity {

    @Id
    @Column(length = 64)
    private String id;

    /** 共享密钥（shk-... 格式） */
    @Column(length = 128, nullable = false, unique = true)
    private String sharingKey;

    /** 关联的 Agent ID */
    @Column(length = 64, nullable = false)
    private String agentId;

    /** Agent 所有者用户 ID */
    @Column(length = 64, nullable = false)
    private String ownerUserId;

    /** 标签/描述（如 "给张三用的"） */
    @Column(length = 128)
    private String label;

    /** 可选系统提示词（注入到用户提问前面作为约束指令） */
    @Column(columnDefinition = "TEXT")
    private String systemPrompt;

    /** Claude Worker 最大轮数 */
    @Column(nullable = false)
    private Integer maxTurns;

    /** 每日调用次数限额 */
    @Column(nullable = false)
    private Integer maxDailyCalls;

    /** 今日已使用次数 */
    @Column(nullable = false)
    private Integer todayCalls;

    /** 计数日期（用于每日重置 todayCalls） */
    private LocalDate callDate;

    /** 过期时间（可选，null 表示永不过期） */
    private LocalDateTime expiresAt;

    /** 是否启用 */
    @Column(nullable = false)
    private Boolean enabled;

    /**
     * 允许的操作列表（逗号分隔），null 表示允许全部操作。
     * <p>
     * 有效操作标识：ask, task:get, task:cancel, task:respond, task:artifacts, session:get,
     * files:read, files:list, files:search
     */
    @Column(length = 512)
    private String allowedOperations;

    /** 最后使用时间 */
    private LocalDateTime lastUsedAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (enabled == null) enabled = true;
        if (maxTurns == null) maxTurns = 1;
        if (maxDailyCalls == null) maxDailyCalls = 50;
        if (todayCalls == null) todayCalls = 0;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
