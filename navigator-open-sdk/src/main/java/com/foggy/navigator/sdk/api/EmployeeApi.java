package com.foggy.navigator.sdk.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.foggy.navigator.sdk.internal.HttpHelper;
import com.foggy.navigator.sdk.model.ExternalUser;
import com.foggy.navigator.sdk.model.ProvisionResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 员工管理 API
 *
 * <pre>
 * // 一站式创建员工（用户 + 目录 + Agent）
 * ProvisionResult emp = client.employees().provision(
 *     "EMP-001", "张三", "worker-id", "/workspace/emp-001",
 *     "my-project", Map.of("CLAUDE.md", "# Project"));
 *
 * // 后续用 emp.getAgentId() 发送任务
 * </pre>
 */
public class EmployeeApi {

    private final HttpHelper http;

    public EmployeeApi(HttpHelper http) {
        this.http = http;
    }

    /**
     * 一站式 Provisioning：创建用户 + 工作目录 + Agent（幂等）
     *
     * @param externalUserId 第三方员工 ID
     * @param displayName    显示名（可选）
     * @param workerId       Worker ID
     * @param directoryPath  工作目录路径
     * @param projectName    项目名（可选）
     * @param files          初始文件（CLAUDE.md 等）
     */
    public ProvisionResult provision(String externalUserId, String displayName,
                                     String workerId, String directoryPath,
                                     String projectName, Map<String, String> files) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("externalUserId", externalUserId);
        if (displayName != null) body.put("displayName", displayName);
        body.put("workerId", workerId);
        body.put("directoryPath", directoryPath);
        if (projectName != null) body.put("projectName", projectName);
        body.put("files", files != null ? files : Map.of("CLAUDE.md", "# Project"));
        return http.post("/api/v1/open/provision/employee", body, new TypeReference<>() {});
    }

    /**
     * 一站式 Provisioning（简化版）
     */
    public ProvisionResult provision(String externalUserId, String workerId,
                                     String directoryPath, Map<String, String> files) {
        return provision(externalUserId, null, workerId, directoryPath, null, files);
    }

    /**
     * 列出所有外部用户
     */
    public List<ExternalUser> list() {
        return http.get("/api/v1/open/users", new TypeReference<>() {});
    }

    /**
     * 获取外部用户详情
     */
    public ExternalUser get(String externalUserId) {
        return http.get("/api/v1/open/users/" + externalUserId, new TypeReference<>() {});
    }

    /**
     * 删除外部用户映射
     */
    public void delete(String externalUserId) {
        http.delete("/api/v1/open/users/" + externalUserId);
    }
}
