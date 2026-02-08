package com.foggy.navigator.spi.claude;

import java.util.List;
import java.util.Map;

/**
 * Claude Worker 门面接口
 * 供其他模块通过 SPI 调用 Claude Worker 功能
 */
public interface ClaudeWorkerFacade {

    /**
     * 列出用户的所有 Worker
     */
    List<Map<String, Object>> listWorkers(String userId);

    /**
     * 获取 Worker 详情
     */
    Map<String, Object> getWorker(String userId, String workerId);

    /**
     * 创建任务
     */
    Map<String, Object> createTask(String userId, Map<String, Object> params);

    /**
     * 获取任务状态
     */
    Map<String, Object> getTaskStatus(String userId, String taskId);

    /**
     * 中止任务
     */
    Map<String, Object> abortTask(String userId, String taskId);

    /**
     * 恢复会话
     */
    Map<String, Object> resumeSession(String userId, Map<String, Object> params);

    /**
     * 列出 Worker 上的 Claude Code 会话
     */
    List<Map<String, Object>> listWorkerSessions(String userId, String workerId);
}
