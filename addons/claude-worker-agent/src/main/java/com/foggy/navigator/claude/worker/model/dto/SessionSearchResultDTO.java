package com.foggy.navigator.claude.worker.model.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话搜索结果 DTO。
 * 每条结果代表一个会话（conversation），包含足够信息渲染列表项并导航到该会话。
 */
@Data
@Builder
public class SessionSearchResultDTO {
    private String sessionId;
    private String workerId;
    private String directoryId;
    /** 会话首条提示词（截断至 200 字符） */
    private String firstPrompt;
    /** 用户自定义标题（来自 ConversationConfig） */
    private String customTitle;
    /** 标签列表（来自 ConversationConfig） */
    private List<String> tags;
    /** 交互状态：PROCESSING / AWAITING_REPLY / ON_HOLD / ARCHIVED */
    private String interactionState;
    /** 最新任务 ID */
    private String latestTaskId;
    /** 最新任务状态 */
    private String latestStatus;
    /** 模型名称 */
    private String model;
    /** 平台 LLM 模型配置 ID */
    private String modelConfigId;
    /** 工作目录路径 */
    private String cwd;
    /** 任务来源：PLATFORM / SYNCED */
    private String source;
    /** 会话总费用 */
    private BigDecimal totalCost;
    /** 会话最早任务创建时间 */
    private LocalDateTime createdAt;
    /** 最新任务更新时间 */
    private LocalDateTime updatedAt;

    /**
     * 搜索结果分页包装
     */
    @Data
    @Builder
    public static class Page {
        private List<SessionSearchResultDTO> results;
        private long total;
        private int page;
        private int size;
    }
}
