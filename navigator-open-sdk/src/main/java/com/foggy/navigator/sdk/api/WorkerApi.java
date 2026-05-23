package com.foggy.navigator.sdk.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.foggy.navigator.sdk.internal.HttpHelper;
import com.foggy.navigator.sdk.model.Worker;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Worker 管理 API
 *
 * <pre>
 * // 注册 Worker
 * Worker worker = client.workers().create("GPU-01", "http://10.0.1.1:3031", "worker-token");
 *
 * // 健康检查
 * Worker updated = client.workers().healthCheck(worker.getWorkerId());
 *
 * // 列出进程
 * Map&lt;String, Object&gt; processes = client.workers().listProcesses(worker.getWorkerId());
 * </pre>
 */
public class WorkerApi {

    private final HttpHelper http;

    public WorkerApi(HttpHelper http) {
        this.http = http;
    }

    /**
     * 注册 Worker
     */
    public Worker create(String name, String baseUrl, String authToken) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("baseUrl", baseUrl);
        body.put("authToken", authToken);
        return http.post("/api/v1/open/workers", body, new TypeReference<>() {});
    }

    /**
     * 注册 Worker（完整参数）
     */
    public Worker create(Map<String, Object> params) {
        return http.post("/api/v1/open/workers", params, new TypeReference<>() {});
    }

    /**
     * 列出租户下所有 Worker
     */
    public List<Worker> list() {
        return http.get("/api/v1/open/workers", new TypeReference<>() {});
    }

    /**
     * 获取 Worker 详情
     */
    public Worker get(String workerId) {
        return http.get("/api/v1/open/workers/" + workerId, new TypeReference<>() {});
    }

    /**
     * 更新 Worker
     */
    public Worker update(String workerId, Map<String, Object> params) {
        return http.put("/api/v1/open/workers/" + workerId, params, new TypeReference<>() {});
    }

    /**
     * 删除 Worker
     */
    public void delete(String workerId) {
        http.delete("/api/v1/open/workers/" + workerId);
    }

    /**
     * 执行健康检查
     */
    public Worker healthCheck(String workerId) {
        return http.post("/api/v1/open/workers/" + workerId + "/health-check",
                null, new TypeReference<>() {});
    }

    /**
     * 列出 CLI 进程（含孤儿检测标记）
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> listProcesses(String workerId) {
        return http.get("/api/v1/open/workers/" + workerId + "/processes",
                new TypeReference<>() {});
    }

    /**
     * 终止 CLI 进程
     *
     * @param workerId Worker ID
     * @param pid      进程 PID
     * @param force    是否强制终止（SIGKILL）
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> killProcess(String workerId, int pid, boolean force) {
        Map<String, Object> body = Map.of("force", force);
        return http.post("/api/v1/open/workers/" + workerId + "/processes/" + pid + "/kill",
                body, new TypeReference<>() {});
    }

    public Worker createWithUpstreamAdmin(Map<String, Object> params, String targetTenantId) {
        return http.postWithUpstreamAdminAuth("/api/v1/upstream-admin/workers" + targetTenantQuery(targetTenantId),
                params, null, new TypeReference<>() {});
    }

    public List<Worker> listWithUpstreamAdmin(String targetTenantId) {
        return http.getWithUpstreamAdminAuth("/api/v1/upstream-admin/workers" + targetTenantQuery(targetTenantId),
                null, new TypeReference<>() {});
    }

    public Worker getWithUpstreamAdmin(String workerId) {
        return http.getWithUpstreamAdminAuth("/api/v1/upstream-admin/workers/" + workerId,
                null, new TypeReference<>() {});
    }

    public Worker updateWithUpstreamAdmin(String workerId, Map<String, Object> params) {
        return http.putWithUpstreamAdminAuth("/api/v1/upstream-admin/workers/" + workerId,
                params, null, new TypeReference<>() {});
    }

    public void deleteWithUpstreamAdmin(String workerId) {
        http.deleteWithUpstreamAdminAuth("/api/v1/upstream-admin/workers/" + workerId, null);
    }

    public Worker healthCheckWithUpstreamAdmin(String workerId) {
        return http.postWithUpstreamAdminAuth("/api/v1/upstream-admin/workers/" + workerId + "/health-check",
                null, null, new TypeReference<>() {});
    }

    public Map<String, Object> listProcessesWithUpstreamAdmin(String workerId) {
        return http.getWithUpstreamAdminAuth("/api/v1/upstream-admin/workers/" + workerId + "/processes",
                null, new TypeReference<>() {});
    }

    public Map<String, Object> killProcessWithUpstreamAdmin(String workerId, int pid, boolean force) {
        return http.postWithUpstreamAdminAuth("/api/v1/upstream-admin/workers/" + workerId + "/processes/" + pid + "/kill",
                Map.of("force", force), null, new TypeReference<>() {});
    }

    private String targetTenantQuery(String targetTenantId) {
        if (targetTenantId == null || targetTenantId.isBlank()) {
            return "";
        }
        return "?targetTenantId=" + URLEncoder.encode(targetTenantId, StandardCharsets.UTF_8);
    }
}
