package com.foggy.navigator.spi.task;

import java.util.List;
import java.util.Map;

/**
 * 跨 Agent 任务管理 SPI
 */
public interface AgentTaskManager {

    /**
     * 创建任务追踪记录
     *
     * @return taskId
     */
    String createTask(String parentSessionId, String userId, String sourceAgentId,
                      String targetAgentId, String taskType, String prompt,
                      String targetSessionId, String externalTaskId);

    /**
     * 列出某会话下的所有委派任务
     */
    List<Map<String, Object>> listTasksBySession(String sessionId);

    /**
     * 完成/失败任务
     */
    void completeTask(String taskId, String status, String resultSummary);

    /**
     * 通过外部任务 ID 反查并完成
     */
    void completeByExternalTaskId(String externalTaskId, String taskType, String status, String resultSummary);
}
