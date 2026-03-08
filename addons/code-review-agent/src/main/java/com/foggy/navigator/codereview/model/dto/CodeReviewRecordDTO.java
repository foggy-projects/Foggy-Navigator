package com.foggy.navigator.codereview.model.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 代码审核记录 DTO
 */
@Data
public class CodeReviewRecordDTO {
    private String recordId;
    private String configId;
    private Long gitlabProjectId;
    private Long mrIid;
    private String mrTitle;
    private String sourceBranch;
    private String targetBranch;
    private String action;
    private String status;
    private String workerId;
    private Long durationMs;
    private Integer inlineCommentsCount;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
