package com.foggy.navigator.sdk;

import com.fasterxml.jackson.core.type.TypeReference;
import com.foggy.navigator.sdk.api.*;
import com.foggy.navigator.sdk.internal.HttpHelper;
import com.foggy.navigator.sdk.model.RegisterResult;

import java.time.Duration;
import java.util.Map;

/**
 * Foggy Navigator Open API 客户端
 * <p>
 * 使用 Builder 模式创建：
 * <pre>{@code
 * NavigatorClient client = NavigatorClient.builder()
 *     .baseUrl("http://navigator.example.com:8112")
 *     .apiKey("sk-xxxx")
 *     .build();
 *
 * // Or use a tenant-admin bearer token:
 * NavigatorClient adminClient = NavigatorClient.builder()
 *     .baseUrl("http://navigator.example.com:8112")
 *     .bearerToken("jwt-token")
 *     .build();
 *
 * // Worker 管理
 * Worker worker = client.workers().create("GPU-01", "http://10.0.1.1:3031", "token");
 * client.workers().healthCheck(worker.getWorkerId());
 *
 * // 员工 Provisioning
 * ProvisionResult emp = client.employees().provision(
 *     "EMP-001", "张三", worker.getWorkerId(), "/workspace/emp-001",
 *     "my-project", Map.of("CLAUDE.md", "# Project"));
 *
 * // Upstream-system shared directories are managed by the upstream admin key.
 * // Runtime user flows should use ClientApp owner-aware grants/workspaces.
 *
 * // 发送任务并等待完成
 * AgentTask result = client.agents().askAndWait(
 *     emp.getAgentId(), "帮我写一个 REST API", Duration.ofMinutes(5));
 * System.out.println(result.getResult());
 *
 * // 多轮对话
 * AgentTask result2 = client.agents().askAndWait(
 *     emp.getAgentId(), "加个单元测试",
 *     result.getContextId(), Duration.ofMinutes(5));
 * }</pre>
 */
public class NavigatorClient {

    private final HttpHelper http;
    private final WorkerApi workerApi;
    private final DirectoryApi directoryApi;
    private final EmployeeApi employeeApi;
    private final AgentApi agentApi;
    private final BusinessAgentApi businessAgentApi;

    private NavigatorClient(HttpHelper http) {
        this.http = http;
        this.workerApi = new WorkerApi(http);
        this.directoryApi = new DirectoryApi(http);
        this.employeeApi = new EmployeeApi(http);
        this.agentApi = new AgentApi(http);
        this.businessAgentApi = new BusinessAgentApi(http);
    }

    // ===== API 模块访问 =====

    /** Worker 管理 */
    public WorkerApi workers() { return workerApi; }

    /** 工作目录管理 */
    public DirectoryApi directories() { return directoryApi; }

    /** 员工管理 */
    public EmployeeApi employees() { return employeeApi; }

    /** Agent 交互（A2A 协议） */
    public AgentApi agents() { return agentApi; }

    /** Business Agent 控制面 API（如 ClientApp、授权、Business Function 管理等） */
    public BusinessAgentApi businessAgent() { return businessAgentApi; }

    // ===== 系统注册（静态方法，无需 API Key） =====

    /**
     * 注册第三方系统（无需认证）
     * <p>
     * 返回的 {@link RegisterResult} 包含 tenantId 和 apiKey，
     * 用这个 apiKey 创建后续的 {@code NavigatorClient}。
     *
     * @param baseUrl       Navigator 服务地址
     * @param systemName    系统名称（如 "TMS"）
     * @param adminUsername 管理员用户名
     * @param adminPassword 管理员密码
     */
    public static RegisterResult register(String baseUrl, String systemName,
                                           String adminUsername, String adminPassword) {
        HttpHelper noAuthHttp = new HttpHelper(baseUrl, null, Duration.ofSeconds(30));
        return noAuthHttp.postNoAuth("/api/v1/open/register",
                Map.of("systemName", systemName,
                        "adminUsername", adminUsername,
                        "adminPassword", adminPassword),
                new TypeReference<>() {});
    }

    // ===== Builder =====

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String baseUrl;
        private String apiKey;
        private String bearerToken;
        private String controlApiKey;
        private String operatorApiKey;
        private String upstreamAdminApiKey;
        private String tenantId;
        private Duration timeout;
        private boolean noDefaultAuth;

        private Builder() {}

        /**
         * Navigator 服务地址（如 http://navigator.example.com:8112）
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * API Key（注册时返回的 apiKey）
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * Bearer token（例如当前登录态或本地 dev admin token）。
         * <p>
         * 如果传入值未包含 {@code Bearer } 前缀，SDK 会自动补齐。
         */
        public Builder bearerToken(String bearerToken) {
            this.bearerToken = bearerToken;
            return this;
        }

        /**
         * {@link #bearerToken(String)} 的语义别名，用于控制面 bootstrap 场景。
         */
        public Builder adminToken(String adminToken) {
            return bearerToken(adminToken);
        }

        /**
         * ClientApp-scoped control-plane key.
         * <p>
         * This is intended for upstream-owned setup such as agent bundle sync,
         * function import/grant for the bound ClientApp, upstream-user grants,
         * and E2E model ensure. It is not a tenant-wide admin credential.
         */
        public Builder controlApiKey(String controlApiKey) {
            this.controlApiKey = controlApiKey;
            return this;
        }

        /**
         * Navigator-internal operator key for approval/bootstrap administration.
         * <p>
         * This is sent as {@code X-Navi-Operator-Key}; it is not the upstream
         * {@code NAVI_ADMIN_API_KEY} used to manage ClientApps after approval.
         */
        public Builder operatorApiKey(String operatorApiKey) {
            this.operatorApiKey = operatorApiKey;
            return this;
        }

        /**
         * Upstream system-level ClientApp admin key issued after bootstrap approval.
         * <p>
         * This is sent as {@code X-Navi-Admin-Key}; it is scoped to the approved
         * upstream system, tenant list, and ClientApp namespace.
         */
        public Builder upstreamAdminApiKey(String upstreamAdminApiKey) {
            this.upstreamAdminApiKey = upstreamAdminApiKey;
            return this;
        }

        /**
         * Build a client without a default API key or bearer token.
         * <p>
         * Use this for ClientApp runtime flows where every request supplies
         * request-scoped headers such as {@code X-Client-App-Key} and
         * {@code X-Client-App-Access-Token}.
         */
        public Builder noDefaultAuth() {
            this.noDefaultAuth = true;
            return this;
        }

        public Builder tenantId(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        /**
         * HTTP 请求超时（默认 30 秒）
         */
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public NavigatorClient build() {
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new IllegalArgumentException("baseUrl is required");
            }
            boolean hasApiKey = apiKey != null && !apiKey.isBlank();
            boolean hasBearerToken = bearerToken != null && !bearerToken.isBlank();
            boolean hasControlApiKey = controlApiKey != null && !controlApiKey.isBlank();
            boolean hasOperatorApiKey = operatorApiKey != null && !operatorApiKey.isBlank();
            boolean hasUpstreamAdminApiKey = upstreamAdminApiKey != null && !upstreamAdminApiKey.isBlank();
            if (!hasApiKey && !hasBearerToken && !hasControlApiKey && !hasOperatorApiKey
                    && !hasUpstreamAdminApiKey && !noDefaultAuth) {
                throw new IllegalArgumentException("apiKey, bearerToken, controlApiKey, operatorApiKey, upstreamAdminApiKey, or noDefaultAuth is required");
            }
            return new NavigatorClient(new HttpHelper(baseUrl, apiKey, bearerToken, tenantId,
                    controlApiKey, operatorApiKey, upstreamAdminApiKey, timeout));
        }
    }
}
