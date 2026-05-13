package com.foggy.navigator.claude.worker.model.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Open API 会话摘要 DTO — 会话列表条目
 */
@Data
@Builder
public class OpenSessionSummaryDTO {

    /** 对外会话主键 */
    private String contextId;

    /** Agent ID */
    private String agentId;

    /** 会话标题 */
    private String title;

    /** 会话状态 */
    private String status;

    /** 最近一次任务 ID */
    private String latestTaskId;

    /** Client App 透明会话上下文 */
    private Map<String, Object> clientContext;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 最后更新时间 */
    private LocalDateTime updatedAt;
}
