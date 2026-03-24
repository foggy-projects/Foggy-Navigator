package com.foggy.navigator.codex.worker.model.event;

import lombok.Builder;
import lombok.Data;

/**
 * Codex 任务启动事件（Spring 应用事件）
 * 由 CodexTaskService 发布，CodexStreamRelay 监听
 *
 * @deprecated 使用 {@link com.foggy.navigator.agent.framework.event.WorkerTaskStartEvent} 替代。
 *             WorkerTaskStartEvent 通过 providerConfig Map 组合 Provider 特有字段。
 */
@Deprecated(since = "unified-task-dispatch-refactor")
@Data
@Builder
public class CodexTaskStartEvent {
    private String taskId;
    private String sessionId;
    private String workerId;
    private String prompt;
    private String cwd;
    private String codexThreadId;
    private String model;
    private Integer maxTurns;
    private String apiKey;
}
