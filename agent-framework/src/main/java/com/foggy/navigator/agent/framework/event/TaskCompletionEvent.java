package com.foggy.navigator.agent.framework.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 任务完成事件
 * 当委派的子任务完成（成功/失败）时发布此事件
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskCompletionEvent {

    private String taskId;
    private String parentSessionId;
    private String targetAgentId;
    private String resultSummary;
    private String status;
    private String externalTaskId;
}
