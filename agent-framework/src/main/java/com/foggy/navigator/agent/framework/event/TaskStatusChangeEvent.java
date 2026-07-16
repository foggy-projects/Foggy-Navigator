package com.foggy.navigator.agent.framework.event;

import com.foggy.navigator.agent.framework.diagnostic.ErrorEnvelope;
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
    /** User copied from the persisted provider task, not from a terminal callback body. */
    private String userId;
    /** Trusted tenant copied from the persisted provider task/session. */
    private String tenantId;
    /** Source logical Agent ID when known; this is not a trusted Provider type. */
    private String agentId;         // e.g. "claude-worker"
    private String status;          // RUNNING / COMPLETED / FAILED / AWAITING_PERMISSION / ABORTED
    private String previousStatus;
    private String errorMessage;
    /** Safe structured failure metadata. Raw diagnostic text is never carried here. */
    private ErrorEnvelope error;
    private String interactionState;  // PROCESSING / AWAITING_REPLY / ARCHIVED
    /**
     * Explicit lifecycle contract: false means definitive terminal, true means
     * recoverable, and null means unspecified/non-terminal. Governance must
     * never interpret null as a definitive transition.
     */
    private Boolean recoverable;
}
