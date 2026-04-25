package com.foggy.navigator.spi.worker;

import com.foggy.navigator.common.model.CodexConfig;
import com.foggy.navigator.common.model.GeminiConfig;

import java.util.List;
import java.util.Map;

/**
 * Worker 管理门面接口 —— Agent 无关的 Worker / Directory 管理能力。
 * <p>
 * 所有 Agent addon（Claude / Codex / 未来）共享此接口来管理 Worker 和工作目录。
 * Agent 特有的查询/执行能力在各自的 Facade 中定义。
 */
public interface WorkerManagementFacade {

    /**
     * 列出用户的所有 Worker
     */
    List<Map<String, Object>> listWorkers(String userId);

    /**
     * 获取 Worker 详情
     */
    Map<String, Object> getWorker(String userId, String workerId);

    /**
     * 验证 Worker 属于指定用户
     *
     * @throws IllegalArgumentException 若 Worker 不存在或不属于该用户
     */
    default void validateWorkerOwnership(String userId, String workerId) {
        getWorker(userId, workerId);
    }

    /**
     * 在 Worker 上初始化目录并注册为工作目录
     *
     * @param files 文件相对路径 → 内容 的映射
     * @return 注册的 directoryId
     */
    String initDirectory(String userId, String workerId, String path, Map<String, String> files);

    /**
     * 在 Worker 上初始化目录并注册为工作目录（带自定义项目名称）
     */
    default String initDirectory(String userId, String workerId, String path,
                                  Map<String, String> files, String projectName) {
        return initDirectory(userId, workerId, path, files);
    }

    /**
     * 绑定平台 LLM 配置到工作目录
     */
    default void bindDirectoryModelConfig(String userId, String directoryId, String modelConfigId) {
        // no-op by default
    }

    /**
     * 获取工作目录的实际路径
     */
    default String getDirectoryPath(String userId, String directoryId) {
        return null;
    }

    /**
     * 获取 Worker 的 Codex 配置（authToken 已解密）
     */
    default CodexConfig getCodexConfig(String workerId) {
        return null;
    }

    /**
     * 获取 Worker 的 Gemini 配置（authToken 已解密）
     */
    default GeminiConfig getGeminiConfig(String workerId) {
        return null;
    }

    // ── Shared File Operations（供 SharedFileController 使用） ──

    /**
     * 列出指定目录下的文件
     *
     * @param userId      用户 ID（归属校验）
     * @param directoryId 工作目录 ID
     * @param subPath     子路径（相对于工作目录，可空）
     * @return Worker 返回的文件列表
     */
    default Map<String, Object> listFiles(String userId, String directoryId, String subPath) {
        throw new UnsupportedOperationException("listFiles not supported");
    }

    /**
     * 读取文件内容
     *
     * @param userId      用户 ID（归属校验）
     * @param directoryId 工作目录 ID
     * @param subPath     文件子路径（相对于工作目录）
     * @return Worker 返回的文件内容
     */
    default Map<String, Object> readFile(String userId, String directoryId, String subPath) {
        throw new UnsupportedOperationException("readFile not supported");
    }

    /**
     * 搜索文件名
     *
     * @param userId      用户 ID（归属校验）
     * @param directoryId 工作目录 ID
     * @param query       搜索关键词
     * @return Worker 返回的搜索结果
     */
    default Map<String, Object> searchFiles(String userId, String directoryId, String query) {
        throw new UnsupportedOperationException("searchFiles not supported");
    }
}
