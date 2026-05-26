package com.foggy.navigator.claude.worker.model.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Open API 任务增量消息响应 — 按 taskId + cursor 轮询
 */
@Data
@Builder
public class OpenTaskMessagesResponse {

    /** 任务 ID */
    private String taskId;

    /** 所属会话上下文 */
    private String contextId;

    /** Worker 侧任务 ID */
    private String workerTaskId;

    /** Provider 侧任务 ID（当前等同 workerTaskId，便于上游统一诊断） */
    private String providerTaskId;

    /** 已同步的 Worker 事件序号 */
    private Integer lastAckedSeq;

    /** 模型配置 ID */
    private String modelConfigId;

    /** 模型配置来源 */
    private String modelConfigSource;

    /** Worker 后端类型 */
    private String workerBackend;

    /** Provider 类型 */
    private String providerType;

    /** 任务来源 */
    private String taskSource;

    /** Worker 来源 */
    private String workerSource;

    /** 后端来源 */
    private String backendSource;

    /** 失败阶段：DISPATCH | WORKER_TRANSPORT | RUNTIME | PROVIDER_API */
    private String failureStage;

    /** 脱敏失败摘要 */
    private String failureSummary;

    /** 消息列表（按时间升序） */
    private List<OpenSessionMessageDTO> messages;

    /** 当前任务状态 */
    private String status;

    /** 任务是否已进入终态 */
    private boolean terminal;

    /** 终态状态：COMPLETED | FAILED | CANCELLED */
    private String terminalStatus;

    /** 下一页游标（为 null 表示无更多数据） */
    private String nextCursor;

    /** 是否还有更多消息 */
    private boolean hasMore;
}
