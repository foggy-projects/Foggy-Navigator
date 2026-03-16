package com.foggy.navigator.sdk.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.foggy.navigator.sdk.internal.HttpHelper;
import com.foggy.navigator.sdk.model.Directory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作目录管理 API
 *
 * <pre>
 * // 初始化目录
 * Directory dir = client.directories().init("worker-id", "/workspace/emp-001",
 *     "my-project", Map.of("CLAUDE.md", "# Project\n..."));
 *
 * // 设置环境变量
 * client.directories().updateEnvVars(dir.getDirectoryId(),
 *     Map.of("TMS_API_TOKEN", "xxx", "TMS_BASE_URL", "https://tms.example.com"));
 *
 * // 更新文件
 * client.directories().updateFiles(dir.getDirectoryId(),
 *     Map.of("CLAUDE.md", "# Updated content"));
 * </pre>
 */
public class DirectoryApi {

    private final HttpHelper http;

    public DirectoryApi(HttpHelper http) {
        this.http = http;
    }

    /**
     * 初始化目录（创建目录并写入文件）
     *
     * @param workerId    Worker ID
     * @param path        目录路径
     * @param projectName 项目名（可选）
     * @param files       初始文件：相对路径 → 内容
     */
    public Directory init(String workerId, String path, String projectName, Map<String, String> files) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workerId", workerId);
        body.put("path", path);
        if (projectName != null) body.put("projectName", projectName);
        body.put("files", files);
        return http.post("/api/v1/open/directories/init", body, new TypeReference<>() {});
    }

    /**
     * 列出目录（租户级或按 Worker 筛选）
     */
    public List<Directory> list() {
        return http.get("/api/v1/open/directories", new TypeReference<>() {});
    }

    /**
     * 列出指定 Worker 的目录
     */
    public List<Directory> listByWorker(String workerId) {
        return http.get("/api/v1/open/directories?workerId=" + workerId, new TypeReference<>() {});
    }

    /**
     * 获取目录详情
     */
    public Directory get(String directoryId) {
        return http.get("/api/v1/open/directories/" + directoryId, new TypeReference<>() {});
    }

    /**
     * 删除目录
     */
    public void delete(String directoryId) {
        http.delete("/api/v1/open/directories/" + directoryId);
    }

    /**
     * 设置目录的自定义环境变量
     * <p>
     * 这些变量会在每次 Claude CLI 执行时注入到进程环境中。
     * 传入完整 Map 覆盖所有变量，传空 Map 清除。
     *
     * @param directoryId 目录 ID
     * @param envVars     环境变量 Map（如 {"TMS_API_TOKEN": "xxx"}）
     */
    @SuppressWarnings("unchecked")
    public Map<String, String> updateEnvVars(String directoryId, Map<String, String> envVars) {
        return http.put("/api/v1/open/directories/" + directoryId + "/env",
                envVars, new TypeReference<>() {});
    }

    /**
     * 更新目录中的文件（覆盖写入）
     * <p>
     * 支持更新 CLAUDE.md、.claude/skills/ 等文件。
     *
     * @param directoryId 目录 ID
     * @param files       文件 Map：相对路径 → 内容
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> updateFiles(String directoryId, Map<String, String> files) {
        return http.put("/api/v1/open/directories/" + directoryId + "/files",
                files, new TypeReference<>() {});
    }
}
