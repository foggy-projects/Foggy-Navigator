package com.foggy.navigator.spi.claude;

import com.foggy.navigator.common.model.CodexConfig;

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
     * @param maxTurns 最大轮次（1=纯文本分析，>1 允许工具调用）
     * @return Map: {resultText, claudeSessionId, costUsd, durationMs, error}
     */
    Map<String, Object> syncQuery(String userId, String workerId, String prompt,
                                   String cwd, String claudeSessionId, int maxTurns,
                                   String model);

    /**
     * 同步查询（带 claude_tasks 记录） — 在 syncQuery 基础上创建/完成任务记录，
     * 使 Workers 页"历史会话"面板能按目录查到这些轻量查询。
     *
     * @param sessionId   Foggy 会话 ID（所有助手任务共用同一会话）
     * @param directoryId 工作目录 ID（让 Workers 页按目录筛选到）
     * @return Map: {resultText, claudeSessionId, costUsd, durationMs, error, taskId}
     */
    default Map<String, Object> syncQueryTracked(
            String userId, String workerId, String prompt,
            String cwd, String claudeSessionId, int maxTurns,
            String model, String sessionId, String directoryId) {
        return syncQuery(userId, workerId, prompt, cwd, claudeSessionId, maxTurns, model);
    }

    /**
     * 在 Worker 上初始化目录并注册为工作目录
     * @param files 文件相对路径 → 内容 的映射
     * @return 注册的 directoryId
     */
    String initDirectory(String userId, String workerId, String path, Map<String, String> files);

    /**
     * 绑定平台 LLM 配置到工作目录（设置 defaultModelConfigId，清空手动 auth）
     */
    default void bindDirectoryModelConfig(String userId, String directoryId, String modelConfigId) {
        // no-op by default
    }

    /**
     * 获取工作目录的实际路径（Worker 上展开后的绝对路径）
     */
    default String getDirectoryPath(String userId, String directoryId) {
        return null;
    }

    /**
     * 获取 Worker 的 Codex 配置（authToken 已解密）
     *
     * @param workerId Worker ID
     * @return 解密后的 CodexConfig，若未配置则返回 null
     */
    default CodexConfig getCodexConfig(String workerId) {
        return null;
    }

    /**
     * 验证 Worker 属于指定用户
     *
     * @throws IllegalArgumentException 若 Worker 不存在或不属于该用户
     */
    default void validateWorkerOwnership(String userId, String workerId) {
        getWorker(userId, workerId); // 默认走 getWorker 校验
    }
}
