package com.foggy.navigator.spi.claude;

import com.foggy.navigator.spi.worker.WorkerManagementFacade;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Claude Worker 门面接口 —— 继承 WorkerManagementFacade，仅保留 Claude 特有方法。
 * <p>
 * Worker/Directory 管理方法在 {@link WorkerManagementFacade} 中定义。
 */
public interface ClaudeWorkerFacade extends WorkerManagementFacade {

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

    /**
     * 列出用户的所有任务
     */
    List<Map<String, Object>> listTasks(String userId);

    /**
     * 同步查询 — 发送 prompt 到 Worker，阻塞等待完成返回结果
     */
    Map<String, Object> syncQuery(String userId, String workerId, String prompt,
                                   String cwd, String claudeSessionId, int maxTurns,
                                   String model);

    /**
     * 同步查询（带 claude_tasks 记录）
     */
    default Map<String, Object> syncQueryTracked(
            String userId, String workerId, String prompt,
            String cwd, String claudeSessionId, int maxTurns,
            String model, String sessionId, String directoryId) {
        return syncQuery(userId, workerId, prompt, cwd, claudeSessionId, maxTurns, model);
    }

    /**
     * 异步查询 — 30 分钟超时，适用于 A2A 长任务
     */
    default CompletableFuture<Map<String, Object>> asyncQuery(
            String userId, String workerId, String prompt, String cwd,
            String claudeSessionId, int maxTurns, String model,
            String directoryId) {
        return CompletableFuture.completedFuture(
                syncQuery(userId, workerId, prompt, cwd, claudeSessionId, maxTurns, model));
    }
}
