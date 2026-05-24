package com.foggy.navigator.sdk.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.foggy.navigator.sdk.internal.HttpHelper;
import com.foggy.navigator.sdk.model.Directory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作目录管理 API.
 *
 * <pre>
 * // Upstream system admin directory initialization.
 * Directory dir = client.directories().initWithUpstreamAdmin(Map.of(
 *     "targetTenantId", "tenant-1",
 *     "workerId", "worker-id",
 *     "path", "/workspace/emp-001",
 *     "projectName", "my-project",
 *     "files", Map.of("CLAUDE.md", "# Project\n...")));
 *
 * // Update environment variables.
 * client.directories().updateEnvVarsWithUpstreamAdmin(dir.getDirectoryId(),
 *     Map.of("TMS_API_TOKEN", "xxx", "TMS_BASE_URL", "https://tms.example.com"));
 *
 * // Update files.
 * client.directories().updateFilesWithUpstreamAdmin(dir.getDirectoryId(),
 *     Map.of("CLAUDE.md", "# Updated content"));
 *
 * // ClientApp-scoped shared or user-private workspace initialization.
 * Directory appDir = client.directories().initWithClientAppControl("tms-app", Map.of(
 *     "workerId", "worker-id",
 *     "workspaceScope", "CLIENT_APP_SHARED",
 *     "path", "/workspace/tms/shared",
 *     "projectName", "tms-shared",
 *     "files", Map.of("CLAUDE.md", "# Project\n...")));
 * </pre>
 */
public class DirectoryApi {

    private final HttpHelper http;

    public DirectoryApi(HttpHelper http) {
        this.http = http;
    }

    /**
     * @deprecated The tenant-level open directory API has been removed. Use
     * {@link #initWithUpstreamAdmin(Map)} or owner-aware ClientApp workspace APIs.
     *
     */
    @Deprecated(forRemoval = true)
    public Directory init(String workerId, String path, String projectName, Map<String, String> files) {
        throw legacyDirectoryApiRemoved();
    }

    /**
     * @deprecated The tenant-level open directory API has been removed. Use
     * {@link #listWithUpstreamAdmin(String, String)} or owner-aware ClientApp workspace APIs.
     */
    @Deprecated(forRemoval = true)
    public List<Directory> list() {
        throw legacyDirectoryApiRemoved();
    }

    /**
     * @deprecated The tenant-level open directory API has been removed. Use
     * {@link #listWithUpstreamAdmin(String, String)} or owner-aware ClientApp workspace APIs.
     */
    @Deprecated(forRemoval = true)
    public List<Directory> listByWorker(String workerId) {
        throw legacyDirectoryApiRemoved();
    }

    /**
     * @deprecated The tenant-level open directory API has been removed. Use
     * {@link #getWithUpstreamAdmin(String)} or owner-aware ClientApp workspace APIs.
     */
    @Deprecated(forRemoval = true)
    public Directory get(String directoryId) {
        throw legacyDirectoryApiRemoved();
    }

    /**
     * @deprecated The tenant-level open directory API has been removed. Use
     * {@link #deleteWithUpstreamAdmin(String)} or owner-aware ClientApp workspace APIs.
     */
    @Deprecated(forRemoval = true)
    public void delete(String directoryId) {
        throw legacyDirectoryApiRemoved();
    }

    /**
     * @deprecated The tenant-level open directory API has been removed. Use
     * {@link #updateEnvVarsWithUpstreamAdmin(String, Map)} or owner-aware ClientApp workspace APIs.
     */
    @Deprecated(forRemoval = true)
    public Map<String, String> updateEnvVars(String directoryId, Map<String, String> envVars) {
        throw legacyDirectoryApiRemoved();
    }

    /**
     * @deprecated The tenant-level open directory API has been removed. Use
     * {@link #updateFilesWithUpstreamAdmin(String, Map)} or owner-aware ClientApp workspace APIs.
     */
    @Deprecated(forRemoval = true)
    public Map<String, Object> updateFiles(String directoryId, Map<String, String> files) {
        throw legacyDirectoryApiRemoved();
    }

    public Directory initWithUpstreamAdmin(Map<String, Object> params) {
        return http.postWithUpstreamAdminAuth("/api/v1/upstream-admin/directories/init",
                params, null, new TypeReference<>() {});
    }

    public List<Directory> listWithUpstreamAdmin(String targetTenantId, String workerId) {
        return http.getWithUpstreamAdminAuth("/api/v1/upstream-admin/directories" + query(targetTenantId, workerId),
                null, new TypeReference<>() {});
    }

    public Directory getWithUpstreamAdmin(String directoryId) {
        return http.getWithUpstreamAdminAuth("/api/v1/upstream-admin/directories/" + directoryId,
                null, new TypeReference<>() {});
    }

    public void deleteWithUpstreamAdmin(String directoryId) {
        http.deleteWithUpstreamAdminAuth("/api/v1/upstream-admin/directories/" + directoryId, null);
    }

    public Map<String, String> updateEnvVarsWithUpstreamAdmin(String directoryId, Map<String, String> envVars) {
        return http.putWithUpstreamAdminAuth("/api/v1/upstream-admin/directories/" + directoryId + "/env",
                envVars, null, new TypeReference<>() {});
    }

    public Map<String, Object> updateFilesWithUpstreamAdmin(String directoryId, Map<String, String> files) {
        return http.putWithUpstreamAdminAuth("/api/v1/upstream-admin/directories/" + directoryId + "/files",
                files, null, new TypeReference<>() {});
    }

    public Directory initWithClientAppControl(String clientAppId, Map<String, Object> params) {
        return http.post(clientAppDirectoryPath(clientAppId) + "/init",
                params, new TypeReference<>() {});
    }

    public List<Directory> listWithClientAppControl(String clientAppId,
                                                    String workerId,
                                                    String workspaceScope,
                                                    String upstreamUserId) {
        Map<String, String> values = new LinkedHashMap<>();
        putIfPresent(values, "workerId", workerId);
        putIfPresent(values, "workspaceScope", workspaceScope);
        putIfPresent(values, "upstreamUserId", upstreamUserId);
        return http.get(clientAppDirectoryPath(clientAppId) + query(values),
                new TypeReference<>() {});
    }

    public Directory getWithClientAppControl(String clientAppId, String directoryId) {
        return http.get(clientAppDirectoryPath(clientAppId) + "/" + encode(directoryId),
                new TypeReference<>() {});
    }

    public void deleteWithClientAppControl(String clientAppId, String directoryId) {
        http.delete(clientAppDirectoryPath(clientAppId) + "/" + encode(directoryId));
    }

    public Map<String, String> updateEnvVarsWithClientAppControl(
            String clientAppId, String directoryId, Map<String, String> envVars) {
        return http.put(clientAppDirectoryPath(clientAppId) + "/" + encode(directoryId) + "/env",
                envVars, new TypeReference<>() {});
    }

    public Map<String, Object> updateFilesWithClientAppControl(
            String clientAppId, String directoryId, Map<String, String> files) {
        return http.put(clientAppDirectoryPath(clientAppId) + "/" + encode(directoryId) + "/files",
                files, new TypeReference<>() {});
    }

    private String query(String targetTenantId, String workerId) {
        Map<String, String> values = new LinkedHashMap<>();
        putIfPresent(values, "targetTenantId", targetTenantId);
        putIfPresent(values, "workerId", workerId);
        return query(values);
    }

    private String query(Map<String, String> values) {
        if (values.isEmpty()) {
            return "";
        }
        return "?" + values.entrySet().stream()
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .reduce((a, b) -> a + "&" + b)
                .orElse("");
    }

    private void putIfPresent(Map<String, String> values, String key, String value) {
        if (value != null && !value.isBlank()) {
            values.put(key, value);
        }
    }

    private String clientAppDirectoryPath(String clientAppId) {
        return "/api/v1/client-apps/" + encode(clientAppId) + "/directories";
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private UnsupportedOperationException legacyDirectoryApiRemoved() {
        return new UnsupportedOperationException("LEGACY_API_REMOVED: /api/v1/open/directories/* has been removed. "
                + "Use upstream admin directory methods with X-Navi-Admin-Key, "
                + "or ClientApp owner-aware workspace APIs.");
    }
}
