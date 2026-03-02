package com.foggy.navigator.agent.framework.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 任务状态变更事件 — 所有状态转换均发布此事件
 * 由 TaskUpdateNotifier 监听，通过用户级 SSE 推送给前端
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskStatusChangeEvent {

    private String taskId;
    private String sessionId;
    private String userId;
    private String agentId;         // e.g. "claude-worker"
    private String status;          // RUNNING / COMPLETED / FAILED / AWAITING_PERMISSION / ABORTED
    private String previousStatus;
    private String errorMessage;
    private String interactionState;  // PROCESSING / AWAITING_REPLY / ARCHIVED
}
