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

    /**
     * 列出用户的所有任务
     */
    List<Map<String, Object>> listTasks(String userId);

    /**
     * 同步查询 — 发送 prompt 到 Worker，阻塞等待完成返回结果
     * 用于轻量级 AI 查询（通知生成等），不创建任务记录，不发布事件
     *
     * @return Map: {resultText, claudeSessionId, costUsd, durationMs, error}
     */
    Map<String, Object> syncQuery(String userId, String workerId, String prompt,
                                   String cwd, String claudeSessionId);

    /**
     * 在 Worker 上初始化目录 — 创建目录并写入文件
     * @param files 文件相对路径 → 内容 的映射
     */
    void initDirectory(String userId, String workerId, String path, Map<String, String> files);
}
