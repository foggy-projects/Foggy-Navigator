package com.foggy.navigator.agent.framework.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

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

    /** 通用扩展数据 — 由事件发布方填充领域特定的上下文（如项目名称、Git 分支等） */
    @Builder.Default
    private Map<String, Object> extData = Map.of();
}
