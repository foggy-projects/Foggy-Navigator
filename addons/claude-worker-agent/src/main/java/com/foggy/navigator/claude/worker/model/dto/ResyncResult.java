package com.foggy.navigator.claude.worker.model.dto;

import lombok.Data;

/**
 * 任务重新同步结果
 *
 * action 枚举:
 * - RECONNECTED: CLI 仍活着，已��新连接 SSE（策略 A）
 * - MESSAGES_SYNCED: CLI 已退出，从 JSONL 补齐了消息（策略 B）
 * - ALREADY_ALIGNED: CLI 已退出，消息已完全一致
 * - NO_SESSION_DATA: Worker 没有该会话的 JSONL 记录
 * - WORKER_UNREACHABLE: Worker 服务不可达
 */
@Data
public class ResyncResult {
    private String taskId;
    private String action;
    private CliStatus cliStatus;
    /** 消息同步报告（仅策略 B 时有值） */
    private MessageSyncReport messageSync;
    /** 同步后的任务状态 */
    private String taskStatusAfter;
}
