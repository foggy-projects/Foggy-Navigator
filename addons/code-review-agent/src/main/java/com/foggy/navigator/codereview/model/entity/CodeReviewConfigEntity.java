package com.foggy.navigator.codereview.model.entity;

import com.foggy.navigator.common.util.IdGenerator;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 代码审核配置 - 每个 GitLab 项目一条配置
 */
@Data
@Entity
@Table(name = "code_review_config", indexes = {
        @Index(name = "idx_cr_config_tenant", columnList = "tenantId"),
        @Index(name = "idx_cr_config_project", columnList = "gitlabProjectId")
})
public class CodeReviewConfigEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(length = 64, nullable = false)
    private String tenantId;

    @Column(length = 64, nullable = false)
    private String userId;

    /** FK → git_provider_config.id（用于获取 GitLab API token） */
    @Column(length = 64, nullable = false)
    private String gitProviderConfigId;

    /** GitLab 项目数字 ID */
    @Column(nullable = false)
    private Long gitlabProjectId;

    /** 项目显示名称（如 path_with_namespace） */
    @Column(length = 256)
    private String projectName;

    /** 使用哪个 Claude Worker 执行审核 */
    @Column(length = 64, nullable = false)
    private String workerId;

    /** 加密存储的 Webhook Secret Token（对应 GitLab 的 X-Gitlab-Token） */
    @Column(length = 512, nullable = false)
    private String webhookSecretToken;

    /** 逗号分隔的触发事件: "open,reopen,update" */
    @Column(length = 256)
    private String triggerEvents;

    /** 审核评论语言: "zh" 或 "en" */
    @Column(length = 32)
    private String reviewLanguage;

    /** 最大 diff 行数（超出截断） */
    private Integer maxDiffLines;

    /** 启停开关 */
    @Column(nullable = false)
    private Boolean isActive;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = IdGenerator.shortId();
        }
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (isActive == null) {
            isActive = true;
        }
        if (triggerEvents == null) {
            triggerEvents = "open,reopen";
        }
        if (reviewLanguage == null) {
            reviewLanguage = "zh";
        }
        if (maxDiffLines == null) {
            maxDiffLines = 3000;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
