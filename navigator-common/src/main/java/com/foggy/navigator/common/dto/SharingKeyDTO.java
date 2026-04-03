package com.foggy.navigator.common.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 共享密钥 DTO
 */
@Data
public class SharingKeyDTO {

    private String id;

    /** 关联的 Agent ID */
    private String agentId;

    /** Agent 名称（冗余展示） */
    private String agentName;

    /** Agent 所有者 */
    private String ownerUserId;

    /** 标签/描述 */
    private String label;

    /** 系统提示词 */
    private String systemPrompt;

    /** Claude Worker 最大轮数 */
    private Integer maxTurns;

    /** 每日调用次数限额 */
    private Integer maxDailyCalls;

    /** 今日已使用次数 */
    private Integer todayCalls;

    /** 过期时间 */
    private LocalDateTime expiresAt;

    /** 是否启用 */
    private Boolean enabled;

    /** 最后使用时间 */
    private LocalDateTime lastUsedAt;

    private LocalDateTime createdAt;

    /** 明文共享密钥（仅创建时返回一次） */
    private String sharingKey;

    /** 掩码后的密钥（列表展示用） */
    private String maskedKey;

    /**
     * 允许的操作列表（如 ["ask", "task:get", "task:cancel"]），null 表示允许全部。
     */
    private java.util.List<String> allowedOperations;

    /** Navigator 对外可达地址（由 navigator.api.external-url 配置） */
    private String invokeBaseUrl;

    /** 完整的 shared ask 调用地址（invokeBaseUrl + /api/v1/shared/ask） */
    private String invokeUrl;
}
