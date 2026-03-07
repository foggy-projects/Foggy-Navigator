package com.foggy.navigator.spi.codex;

import java.util.List;
import java.util.Map;

/**
 * Codex Worker 门面接口
 * 供其他模块通过 SPI 调用 Codex Worker 功能
 */
public interface CodexWorkerFacade {

    /**
     * 列出用户的所有 Codex Worker
     */
    List<Map<String, Object>> listWorkers(String userId);

    /**
     * 获取 Codex Worker 详情
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
     * 同步查询 — 发送 prompt 到 Codex Worker，阻塞等待完成返回结果
     * 用于轻量级 AI 查询，不创建任务记录，不发布事件
     *
     * @param codexThreadId Codex SDK thread ID（null 表示新会话）
     * @param maxTurns      最大轮次（1=纯文本分析，>1 允许工具调用）
     * @return Map: {resultText, codexThreadId, costUsd, durationMs, error}
     */
    Map<String, Object> syncQuery(String userId, String workerId, String prompt,
                                   String cwd, String codexThreadId, int maxTurns,
                                   String model);

    /**
     * 同步查询（带 codex_tasks 记录）
     *
     * @param sessionId Foggy 会话 ID
     * @return Map: {resultText, codexThreadId, costUsd, durationMs, error, taskId}
     */
    default Map<String, Object> syncQueryTracked(
            String userId, String workerId, String prompt,
            String cwd, String codexThreadId, int maxTurns,
            String model, String sessionId) {
        return syncQuery(userId, workerId, prompt, cwd, codexThreadId, maxTurns, model);
    }
}
