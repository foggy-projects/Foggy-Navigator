package com.foggy.navigator.codereview.model.entity;

import com.foggy.navigator.codereview.model.enums.ReviewStatus;
import com.foggy.navigator.common.util.IdGenerator;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 代码审核执行记录
 */
@Data
@Entity
@Table(name = "code_review_record", indexes = {
        @Index(name = "idx_crr_config_id", columnList = "configId"),
        @Index(name = "idx_crr_mr", columnList = "gitlabProjectId, mrIid"),
        @Index(name = "idx_crr_status", columnList = "status")
})
public class CodeReviewRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 业务唯一 ID */
    @Column(length = 64, nullable = false, unique = true)
    private String recordId;

    /** FK → code_review_config.id */
    @Column(length = 64, nullable = false)
    private String configId;

    @Column(nullable = false)
    private Long gitlabProjectId;

    /** MR 内部 ID（iid） */
    @Column(nullable = false)
    private Long mrIid;

    @Column(length = 512)
    private String mrTitle;

    @Column(length = 256)
    private String sourceBranch;

    @Column(length = 256)
    private String targetBranch;

    /** 触发动作: open, update, reopen */
    @Column(length = 32)
    private String action;

    /** PENDING / RUNNING / COMPLETED / FAILED */
    @Column(length = 32, nullable = false)
    private String status;

    @Column(length = 64)
    private String workerId;

    /** 审核耗时（毫秒） */
    private Long durationMs;

    /** 发布的 inline 评论数 */
    private Integer inlineCommentsCount;

    /** 错误信息 */
    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (recordId == null) {
            recordId = IdGenerator.shortId();
        }
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (status == null) {
            status = ReviewStatus.PENDING.name();
        }
        if (inlineCommentsCount == null) {
            inlineCommentsCount = 0;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
