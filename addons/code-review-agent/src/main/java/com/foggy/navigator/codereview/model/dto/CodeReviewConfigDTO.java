package com.foggy.navigator.codereview.model.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 代码审核配置 DTO（不含敏感信息）
 */
@Data
public class CodeReviewConfigDTO {
    private String id;
    private String tenantId;
    private String userId;
    private String gitProviderConfigId;
    private Long gitlabProjectId;
    private String projectName;
    private String workerId;
    private String triggerEvents;
    private String reviewLanguage;
    private Integer maxDiffLines;
    private Boolean isActive;
    /** Webhook URL（由服务端拼接，供用户复制到 GitLab） */
    private String webhookUrl;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
