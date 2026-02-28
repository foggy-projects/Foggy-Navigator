package com.foggy.navigator.agent.framework.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 任务开始事件
 * 当委派的子任务开始执行时发布此事件
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskStartedEvent {

    private String taskId;
    private String parentSessionId;
    private String targetAgentId;
    private String prompt;
    private String externalTaskId;
}
