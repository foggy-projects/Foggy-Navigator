package com.foggy.navigator.sdk.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.foggy.navigator.sdk.internal.HttpHelper;
import com.foggy.navigator.sdk.model.businessagent.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Foggy Navigator Business Agent 控制面 API
 */
public class BusinessAgentApi {

    private static final String OPERATOR_KEY_HEADER = "X-Navi-Operator-Key";

    private final HttpHelper http;

    public BusinessAgentApi(HttpHelper http) {
        this.http = http;
    }

    // ===== ClientApp =====

    public List<ClientAppDTO> listClientApps() {
        return http.get("/api/v1/client-apps", new TypeReference<>() {});
    }

    public ClientAppDTO createClientApp(CreateClientAppForm form) {
        return http.post("/api/v1/client-apps", form, new TypeReference<>() {});
    }

    public IssuedCredentialDTO issueProvisioningCredential(IssueProvisioningCredentialForm form) {
        return http.post("/api/v1/admin/client-apps/provisioning-credentials", form, new TypeReference<>() {});
    }

    public IssuedCredentialDTO issueRuntimeCredential(String clientAppId, IssueRuntimeCredentialForm form) {
        return http.post("/api/v1/client-apps/" + clientAppId + "/runtime-credentials", form, new TypeReference<>() {});
    }

    public IssuedCredentialDTO issueControlCredential(String clientAppId, IssueControlCredentialForm form) {
        return http.post("/api/v1/client-apps/" + clientAppId + "/control-credentials", form, new TypeReference<>() {});
    }

    public ClientAppRuntimeAccessTokenDTO exchangeRuntimeAccessToken(String appKey, String appSecret) {
        return http.post("/api/v1/open/client-apps/runtime-token", null, Map.of(
                "X-Client-App-Key", appKey,
                "X-Client-App-Secret", appSecret
        ), new TypeReference<>() {});
    }

    public ClientAppDTO updateClientAppStatus(String clientAppId, UpdateStatusForm form) {
        return http.put("/api/v1/client-apps/" + clientAppId + "/status", form, new TypeReference<>() {});
    }

    public UpstreamTenantClientAppProvisioningDTO ensureUpstreamTenantClientApp(
            EnsureUpstreamTenantClientAppForm form) {
        return ensureUpstreamTenantClientApp(form, null);
    }

    public UpstreamTenantClientAppProvisioningDTO ensureUpstreamTenantClientApp(
            EnsureUpstreamTenantClientAppForm form,
            String upstreamAdminApiKey) {
        return http.postWithUpstreamAdminAuth("/api/v1/admin/upstream-tenants/client-apps/ensure",
                form, upstreamAdminApiKey, new TypeReference<>() {});
    }

    // ===== Upstream Admin ClientApp Management =====

    public List<ClientAppDTO> listUpstreamManagedClientApps(String tenantId) {
        return listUpstreamManagedClientApps(tenantId, null);
    }

    public List<ClientAppDTO> listUpstreamManagedClientApps(String tenantId, String upstreamAdminApiKey) {
        String path = "/api/v1/upstream-admin/client-apps";
        if (tenantId != null && !tenantId.isBlank()) {
            path += "?tenantId=" + urlEncode(tenantId);
        }
        return http.getWithUpstreamAdminAuth(path, upstreamAdminApiKey, new TypeReference<>() {});
    }

    public ClientAppDTO ensureUpstreamClientApp(EnsureUpstreamClientAppForm form) {
        return ensureUpstreamClientApp(form, null);
    }

    public ClientAppDTO ensureUpstreamClientApp(EnsureUpstreamClientAppForm form, String upstreamAdminApiKey) {
        return http.postWithUpstreamAdminAuth("/api/v1/upstream-admin/client-apps/ensure",
                form, upstreamAdminApiKey, new TypeReference<>() {});
    }

    public IssuedCredentialDTO issueUpstreamClientAppControlCredential(String clientAppId,
                                                                       IssueControlCredentialForm form) {
        return issueUpstreamClientAppControlCredential(clientAppId, form, null);
    }

    public IssuedCredentialDTO issueUpstreamClientAppControlCredential(String clientAppId,
                                                                       IssueControlCredentialForm form,
                                                                       String upstreamAdminApiKey) {
        return http.postWithUpstreamAdminAuth("/api/v1/upstream-admin/client-apps/" + urlEncode(clientAppId)
                + "/control-credentials", form, upstreamAdminApiKey, new TypeReference<>() {});
    }

    public IssuedCredentialDTO issueUpstreamClientAppRuntimeCredential(String clientAppId,
                                                                       IssueRuntimeCredentialForm form) {
        return issueUpstreamClientAppRuntimeCredential(clientAppId, form, null);
    }

    public IssuedCredentialDTO issueUpstreamClientAppRuntimeCredential(String clientAppId,
                                                                       IssueRuntimeCredentialForm form,
                                                                       String upstreamAdminApiKey) {
        return http.postWithUpstreamAdminAuth("/api/v1/upstream-admin/client-apps/" + urlEncode(clientAppId)
                + "/runtime-credentials", form, upstreamAdminApiKey, new TypeReference<>() {});
    }

    public Map<String, Object> registerUpstreamWorkerIdentity(Map<String, Object> form) {
        return http.postWithUpstreamAdminAuth("/api/v1/upstream-admin/worker-identities",
                form, null, new TypeReference<>() {});
    }

    public List<LlmModelConfigDTO> listUpstreamSystemModelConfigs(String targetTenantId) {
        String path = "/api/v1/upstream-admin/model-configs";
        if (targetTenantId != null && !targetTenantId.isBlank()) {
            path += "?targetTenantId=" + urlEncode(targetTenantId);
        }
        return http.getWithUpstreamAdminAuth(path, null, new TypeReference<>() {});
    }

    public LlmModelConfigDTO createUpstreamSystemModelConfig(ClientAppModelConfigForm form, String targetTenantId) {
        String path = "/api/v1/upstream-admin/model-configs";
        if (targetTenantId != null && !targetTenantId.isBlank()) {
            path += "?targetTenantId=" + urlEncode(targetTenantId);
        }
        return http.postWithUpstreamAdminAuth(path, form, null, new TypeReference<>() {});
    }

    public LlmModelConfigDTO updateUpstreamSystemModelConfig(String modelConfigId,
                                                             ClientAppModelConfigForm form,
                                                             String targetTenantId) {
        String path = "/api/v1/upstream-admin/model-configs/" + urlEncode(modelConfigId);
        if (targetTenantId != null && !targetTenantId.isBlank()) {
            path += "?targetTenantId=" + urlEncode(targetTenantId);
        }
        return http.putWithUpstreamAdminAuth(path, form, null, new TypeReference<>() {});
    }

    public LlmModelConfigDTO rotateUpstreamSystemModelConfigKey(String modelConfigId,
                                                                RotateModelConfigKeyForm form,
                                                                String targetTenantId) {
        String path = "/api/v1/upstream-admin/model-configs/" + urlEncode(modelConfigId) + "/key";
        if (targetTenantId != null && !targetTenantId.isBlank()) {
            path += "?targetTenantId=" + urlEncode(targetTenantId);
        }
        return http.putWithUpstreamAdminAuth(path, form, null, new TypeReference<>() {});
    }

    public List<Map<String, Object>> listUpstreamWorkerPools(String targetTenantId) {
        String path = "/api/v1/upstream-admin/worker-pools";
        if (targetTenantId != null && !targetTenantId.isBlank()) {
            path += "?targetTenantId=" + urlEncode(targetTenantId);
        }
        return http.getWithUpstreamAdminAuth(path, null, new TypeReference<>() {});
    }

    public Map<String, Object> createUpstreamWorkerPool(Map<String, Object> form, String targetTenantId) {
        String path = "/api/v1/upstream-admin/worker-pools";
        if (targetTenantId != null && !targetTenantId.isBlank()) {
            path += "?targetTenantId=" + urlEncode(targetTenantId);
        }
        return http.postWithUpstreamAdminAuth(path, form, null, new TypeReference<>() {});
    }

    public void addUpstreamWorkerPoolMember(String poolId, Map<String, Object> form, String targetTenantId) {
        String path = "/api/v1/upstream-admin/worker-pools/" + urlEncode(poolId) + "/members";
        if (targetTenantId != null && !targetTenantId.isBlank()) {
            path += "?targetTenantId=" + urlEncode(targetTenantId);
        }
        http.postWithUpstreamAdminAuth(path, form, null, new TypeReference<Void>() {});
    }

    public Map<String, Object> updateUpstreamWorkerPoolStatus(String poolId, String status, String targetTenantId) {
        String path = "/api/v1/upstream-admin/worker-pools/" + urlEncode(poolId) + "/status";
        if (targetTenantId != null && !targetTenantId.isBlank()) {
            path += "?targetTenantId=" + urlEncode(targetTenantId);
        }
        return http.putWithUpstreamAdminAuth(path, Map.of("status", status), null, new TypeReference<>() {});
    }

    // ===== Upstream Bootstrap Admin Key Request =====

    public UpstreamBootstrapRequestCreatedDTO requestUpstreamAdminKey(CreateUpstreamBootstrapRequestForm form) {
        return http.postNoAuth("/api/v1/upstream-bootstrap/admin-key-requests", form, new TypeReference<>() {});
    }

    public UpstreamBootstrapRequestDTO getUpstreamAdminKeyRequestStatus(String requestCode) {
        return http.get("/api/v1/upstream-bootstrap/admin-key-requests/"
                + urlEncode(requestCode) + "/status", new TypeReference<>() {});
    }

    public UpstreamAdminCredentialClaimDTO claimUpstreamAdminKey(String requestCode,
                                                                 ClaimUpstreamAdminCredentialForm form) {
        return http.postNoAuth("/api/v1/upstream-bootstrap/admin-key-requests/"
                + urlEncode(requestCode) + "/claim", form, new TypeReference<>() {});
    }

    public List<UpstreamBootstrapRequestDTO> listUpstreamBootstrapRequests(String status) {
        return listUpstreamBootstrapRequests(status, null);
    }

    public List<UpstreamBootstrapRequestDTO> listUpstreamBootstrapRequests(String status, String operatorApiKey) {
        String path = "/api/v1/admin/upstream-bootstrap-requests";
        if (status != null && !status.isBlank()) {
            path += "?status=" + urlEncode(status);
        }
        return http.get(path, operatorHeaders(operatorApiKey), new TypeReference<>() {});
    }

    public UpstreamBootstrapRequestDTO getUpstreamBootstrapRequest(String requestCode) {
        return getUpstreamBootstrapRequest(requestCode, null);
    }

    public UpstreamBootstrapRequestDTO getUpstreamBootstrapRequest(String requestCode, String operatorApiKey) {
        return http.get("/api/v1/admin/upstream-bootstrap-requests/" + urlEncode(requestCode),
                operatorHeaders(operatorApiKey), new TypeReference<>() {});
    }

    public UpstreamBootstrapRequestDTO approveUpstreamBootstrapRequest(
            String requestCode,
            ApproveUpstreamBootstrapRequestForm form) {
        return approveUpstreamBootstrapRequest(requestCode, form, null);
    }

    public UpstreamBootstrapRequestDTO approveUpstreamBootstrapRequest(
            String requestCode,
            ApproveUpstreamBootstrapRequestForm form,
            String operatorApiKey) {
        return http.post("/api/v1/admin/upstream-bootstrap-requests/"
                + urlEncode(requestCode) + "/approve", form, operatorHeaders(operatorApiKey), new TypeReference<>() {});
    }

    public UpstreamBootstrapRequestDTO denyUpstreamBootstrapRequest(
            String requestCode,
            DenyUpstreamBootstrapRequestForm form) {
        return denyUpstreamBootstrapRequest(requestCode, form, null);
    }

    public UpstreamBootstrapRequestDTO denyUpstreamBootstrapRequest(
            String requestCode,
            DenyUpstreamBootstrapRequestForm form,
            String operatorApiKey) {
        return http.post("/api/v1/admin/upstream-bootstrap-requests/"
                + urlEncode(requestCode) + "/deny", form, operatorHeaders(operatorApiKey), new TypeReference<>() {});
    }

    public UpstreamAdminCredentialDTO revokeUpstreamAdminCredential(String credentialId) {
        return revokeUpstreamAdminCredential(credentialId, null);
    }

    public UpstreamAdminCredentialDTO revokeUpstreamAdminCredential(String credentialId, String operatorApiKey) {
        return http.post("/api/v1/admin/upstream-admin-credentials/"
                + urlEncode(credentialId) + "/revoke", null, operatorHeaders(operatorApiKey), new TypeReference<>() {});
    }

    public UpstreamAdminCredentialClaimDTO rotateUpstreamAdminCredential(String credentialId,
                                                                        RotateUpstreamAdminCredentialForm form) {
        return rotateUpstreamAdminCredential(credentialId, form, null);
    }

    public UpstreamAdminCredentialClaimDTO rotateUpstreamAdminCredential(String credentialId,
                                                                        RotateUpstreamAdminCredentialForm form,
                                                                        String operatorApiKey) {
        return http.post("/api/v1/admin/upstream-admin-credentials/"
                + urlEncode(credentialId) + "/rotate", form, operatorHeaders(operatorApiKey), new TypeReference<>() {});
    }

    // ===== Model Config Grant =====

    public List<ClientAppModelConfigGrantDTO> listModelConfigGrants(String clientAppId) {
        return http.get("/api/v1/client-apps/" + clientAppId + "/model-config-grants", new TypeReference<>() {});
    }

    public ClientAppModelConfigGrantDTO grantModelConfig(String clientAppId, GrantModelConfigForm form) {
        return http.post("/api/v1/client-apps/" + clientAppId + "/model-config-grants", form, new TypeReference<>() {});
    }

    public ClientAppModelConfigGrantDTO updateModelConfigGrantStatus(String clientAppId, Long grantId, UpdateStatusForm form) {
        return http.put("/api/v1/client-apps/" + clientAppId + "/model-config-grants/" + grantId + "/status", form, new TypeReference<>() {});
    }

    public ClientAppModelConfigGrantDTO setDefaultModelConfigGrant(String clientAppId, Long grantId) {
        return http.put("/api/v1/client-apps/" + clientAppId + "/model-config-grants/" + grantId + "/default", null, new TypeReference<>() {});
    }

    public ClientAppModelConfigGrantDTO createClientAppModelConfig(String clientAppId, ClientAppModelConfigForm form) {
        return http.post("/api/v1/client-apps/" + clientAppId + "/model-configs", form, new TypeReference<>() {});
    }

    public ClientAppModelConfigGrantDTO updateClientAppModelConfig(String clientAppId, String modelConfigId,
                                                                   ClientAppModelConfigForm form) {
        return http.put("/api/v1/client-apps/" + clientAppId + "/model-configs/" + modelConfigId, form, new TypeReference<>() {});
    }

    public ClientAppModelConfigGrantDTO rotateClientAppModelConfigKey(String clientAppId, String modelConfigId,
                                                                      RotateModelConfigKeyForm form) {
        return http.put("/api/v1/client-apps/" + clientAppId + "/model-configs/" + modelConfigId + "/key", form, new TypeReference<>() {});
    }

    public E2eModelConfigEnsureResultDTO ensureE2eModelConfig(String clientAppId, EnsureE2eModelConfigForm form) {
        return http.post("/api/v1/business-agent/client-apps/" + clientAppId + "/e2e-model-config/ensure", form, new TypeReference<>() {});
    }

    // ===== Agent Model Binding =====

    public List<AgentModelBindingDTO> listAgentModelBindings(String clientAppId, String agentId) {
        return http.get("/api/v1/client-apps/" + urlEncode(clientAppId)
                + "/agents/" + urlEncode(agentId) + "/model-bindings", new TypeReference<>() {});
    }

    public AgentModelBindingDTO bindAgentModel(String clientAppId, String agentId, BindAgentModelForm form) {
        return http.post("/api/v1/client-apps/" + urlEncode(clientAppId)
                + "/agents/" + urlEncode(agentId) + "/model-bindings", form, new TypeReference<>() {});
    }

    public void unbindAgentModel(String clientAppId, String agentId, String modelConfigId) {
        http.delete("/api/v1/client-apps/" + urlEncode(clientAppId)
                + "/agents/" + urlEncode(agentId) + "/model-bindings/" + urlEncode(modelConfigId));
    }

    public AgentModelBindingDTO setDefaultAgentModel(String clientAppId, String agentId, BindAgentModelForm form) {
        return http.put("/api/v1/client-apps/" + urlEncode(clientAppId)
                + "/agents/" + urlEncode(agentId) + "/model-bindings/default", form, new TypeReference<>() {});
    }

    public List<AgentModelBindingDTO> listUpstreamSystemAgentModelBindings(String agentId, String targetTenantId) {
        return http.getWithUpstreamAdminAuth(upstreamSystemAgentModelBindingPath(agentId, targetTenantId),
                null,
                new TypeReference<>() {});
    }

    public AgentModelBindingDTO bindUpstreamSystemAgentModel(String agentId,
                                                             BindAgentModelForm form,
                                                             String targetTenantId) {
        return http.postWithUpstreamAdminAuth(upstreamSystemAgentModelBindingPath(agentId, targetTenantId),
                form,
                null,
                new TypeReference<>() {});
    }

    public AgentModelBindingDTO setDefaultUpstreamSystemAgentModel(String agentId,
                                                                   BindAgentModelForm form,
                                                                   String targetTenantId) {
        return http.putWithUpstreamAdminAuth(upstreamSystemAgentModelBindingPath(agentId, "default", targetTenantId),
                form,
                null,
                new TypeReference<>() {});
    }

    public void unbindUpstreamSystemAgentModel(String agentId, String modelConfigId, String targetTenantId) {
        http.deleteWithUpstreamAdminAuth(upstreamSystemAgentModelBindingPath(agentId, modelConfigId, targetTenantId), null);
    }

    // ===== Agent Workspace Binding =====

    public List<AgentWorkspaceBindingDTO> listAgentWorkspaceBindings(String clientAppId, String agentId) {
        return http.get("/api/v1/client-apps/" + urlEncode(clientAppId)
                + "/agents/" + urlEncode(agentId) + "/workspace-bindings", new TypeReference<>() {});
    }

    public AgentWorkspaceBindingDTO bindAgentWorkspace(String clientAppId,
                                                       String agentId,
                                                       BindAgentWorkspaceForm form) {
        return http.post("/api/v1/client-apps/" + urlEncode(clientAppId)
                + "/agents/" + urlEncode(agentId) + "/workspace-bindings", form, new TypeReference<>() {});
    }

    public AgentWorkspaceBindingDTO setDefaultAgentWorkspace(String clientAppId,
                                                             String agentId,
                                                             BindAgentWorkspaceForm form) {
        return http.put("/api/v1/client-apps/" + urlEncode(clientAppId)
                + "/agents/" + urlEncode(agentId) + "/workspace-bindings/default", form, new TypeReference<>() {});
    }

    public void unbindAgentWorkspace(String clientAppId, String agentId, String directoryId) {
        http.delete("/api/v1/client-apps/" + urlEncode(clientAppId)
                + "/agents/" + urlEncode(agentId) + "/workspace-bindings/" + urlEncode(directoryId));
    }

    public List<AgentWorkspaceBindingDTO> listUpstreamSystemAgentWorkspaceBindings(String agentId,
                                                                                   String targetTenantId) {
        return http.getWithUpstreamAdminAuth(upstreamSystemAgentWorkspaceBindingPath(agentId, targetTenantId),
                null,
                new TypeReference<>() {});
    }

    public AgentWorkspaceBindingDTO bindUpstreamSystemAgentWorkspace(String agentId,
                                                                     BindAgentWorkspaceForm form,
                                                                     String targetTenantId) {
        return http.postWithUpstreamAdminAuth(upstreamSystemAgentWorkspaceBindingPath(agentId, targetTenantId),
                form,
                null,
                new TypeReference<>() {});
    }

    public AgentWorkspaceBindingDTO setDefaultUpstreamSystemAgentWorkspace(String agentId,
                                                                           BindAgentWorkspaceForm form,
                                                                           String targetTenantId) {
        return http.putWithUpstreamAdminAuth(upstreamSystemAgentWorkspaceBindingPath(agentId, "default", targetTenantId),
                form,
                null,
                new TypeReference<>() {});
    }

    public void unbindUpstreamSystemAgentWorkspace(String agentId, String directoryId, String targetTenantId) {
        http.deleteWithUpstreamAdminAuth(upstreamSystemAgentWorkspaceBindingPath(agentId, directoryId, targetTenantId), null);
    }

    // ===== Agent Worker Binding =====

    public List<AgentWorkerBindingDTO> listAgentWorkerBindings(String clientAppId, String agentId) {
        return http.get("/api/v1/client-apps/" + urlEncode(clientAppId)
                + "/agents/" + urlEncode(agentId) + "/worker-bindings", new TypeReference<>() {});
    }

    public AgentWorkerBindingDTO bindAgentWorker(String clientAppId,
                                                 String agentId,
                                                 BindAgentWorkerForm form) {
        return http.post("/api/v1/client-apps/" + urlEncode(clientAppId)
                + "/agents/" + urlEncode(agentId) + "/worker-bindings", form, new TypeReference<>() {});
    }

    public AgentWorkerBindingDTO setDefaultAgentWorker(String clientAppId,
                                                       String agentId,
                                                       BindAgentWorkerForm form) {
        return http.put("/api/v1/client-apps/" + urlEncode(clientAppId)
                + "/agents/" + urlEncode(agentId) + "/worker-bindings/default", form, new TypeReference<>() {});
    }

    public void unbindAgentWorker(String clientAppId, String agentId, String workerPoolId) {
        http.delete("/api/v1/client-apps/" + urlEncode(clientAppId)
                + "/agents/" + urlEncode(agentId) + "/worker-bindings/" + urlEncode(workerPoolId));
    }

    public List<AgentWorkerBindingDTO> listUpstreamSystemAgentWorkerBindings(String agentId,
                                                                             String targetTenantId) {
        return http.getWithUpstreamAdminAuth(upstreamSystemAgentWorkerBindingPath(agentId, targetTenantId),
                null,
                new TypeReference<>() {});
    }

    public AgentWorkerBindingDTO bindUpstreamSystemAgentWorker(String agentId,
                                                               BindAgentWorkerForm form,
                                                               String targetTenantId) {
        return http.postWithUpstreamAdminAuth(upstreamSystemAgentWorkerBindingPath(agentId, targetTenantId),
                form,
                null,
                new TypeReference<>() {});
    }

    public AgentWorkerBindingDTO setDefaultUpstreamSystemAgentWorker(String agentId,
                                                                     BindAgentWorkerForm form,
                                                                     String targetTenantId) {
        return http.putWithUpstreamAdminAuth(upstreamSystemAgentWorkerBindingPath(agentId, "default", targetTenantId),
                form,
                null,
                new TypeReference<>() {});
    }

    public void unbindUpstreamSystemAgentWorker(String agentId, String workerPoolId, String targetTenantId) {
        http.deleteWithUpstreamAdminAuth(upstreamSystemAgentWorkerBindingPath(agentId, workerPoolId, targetTenantId), null);
    }

    // ===== Upstream System Agent =====

    public List<BusinessAgentBundleDTO> listUpstreamSystemAgents(String targetTenantId) {
        return http.getWithUpstreamAdminAuth(upstreamSystemAgentPath(null, targetTenantId),
                null,
                new TypeReference<>() {});
    }

    public BusinessAgentBundleDTO createUpstreamSystemAgent(UpstreamAgentForm form, String targetTenantId) {
        return http.postWithUpstreamAdminAuth(upstreamSystemAgentPath(null, targetTenantId),
                form,
                null,
                new TypeReference<>() {});
    }

    public BusinessAgentBundleDTO getUpstreamSystemAgent(String agentId, String targetTenantId) {
        return http.getWithUpstreamAdminAuth(upstreamSystemAgentPath(agentId, targetTenantId),
                null,
                new TypeReference<>() {});
    }

    public BusinessAgentBundleDTO updateUpstreamSystemAgent(String agentId,
                                                            UpstreamAgentForm form,
                                                            String targetTenantId) {
        return http.putWithUpstreamAdminAuth(upstreamSystemAgentPath(agentId, targetTenantId),
                form,
                null,
                new TypeReference<>() {});
    }

    // ===== Business Agent Bundle =====

    public BusinessAgentBundleDTO syncBusinessAgentBundle(SyncBusinessAgentBundleForm form) {
        return http.post("/api/v1/business-agent/agent-bundles/sync", form, new TypeReference<>() {});
    }

    // ===== Frame Report =====

    public Map<String, Object> getFrameReport(String reportRef, String mode, int maxChars) {
        return getFrameReport(reportRef, mode, maxChars, null);
    }

    public Map<String, Object> getFrameReport(String reportRef, String mode, int maxChars, String clientAppId) {
        return http.get(frameReportPath(reportRef, mode, maxChars, clientAppId), new TypeReference<>() {});
    }

    public Map<String, Object> getFrameReportWithClientAppAccessToken(
            String reportRef,
            String mode,
            int maxChars,
            String clientAppKey,
            String clientAppAccessToken) {
        return http.get(frameReportPath(reportRef, mode, maxChars, null),
                clientAppHeaders(clientAppKey, clientAppAccessToken),
                new TypeReference<>() {});
    }

    // ===== Skill =====

    public SkillDTO createSkill(CreateSkillForm form) {
        return http.post("/api/v1/business-agent/skills", form, new TypeReference<>() {});
    }

    public SkillFunctionAllowlistDTO addFunctionToSkillAllowlist(String skillId, AddFunctionToSkillForm form) {
        return http.post("/api/v1/business-agent/skills/" + skillId + "/functions", form, new TypeReference<>() {});
    }

    public ClientAppSkillGrantDTO grantSkillToClientApp(String clientAppId, GrantSkillToClientAppForm form) {
        return http.post("/api/v1/business-agent/client-apps/" + clientAppId + "/skill-grants", form, new TypeReference<>() {});
    }

    public SkillMaterializeResultDTO materializePublicSkill(String skillId) {
        return http.post("/api/v1/business-agent/skills/" + skillId + "/materialize", null, new TypeReference<>() {});
    }

    public SkillBundleDTO syncSkillBundle(SyncSkillBundleForm form) {
        return http.post("/api/v1/business-agent/skill-bundles/sync", form, new TypeReference<>() {});
    }

    public SkillClearResultDTO clearPublicSkillBundles(ClearSkillBundleForm form) {
        return http.post("/api/v1/business-agent/skill-bundles/clear-public", form, new TypeReference<>() {});
    }

    public SkillClearResultDTO clearAccountSkillBundles(ClearSkillBundleForm form) {
        return http.post("/api/v1/business-agent/skill-bundles/clear-account", form, new TypeReference<>() {});
    }

    // ===== Upstream User Grant =====

    public ClientAppUpstreamUserGrantDTO grantUpstreamUserAccess(String clientAppId, GrantUpstreamUserForm form) {
        return http.post("/api/v1/business-agent/client-apps/" + clientAppId + "/upstream-users", form, new TypeReference<>() {});
    }

    public ClientAppUpstreamUserGrantDTO updateUpstreamUserGrantStatus(String clientAppId, String upstreamUserId, String status) {
        return http.put("/api/v1/business-agent/client-apps/" + clientAppId + "/upstream-users/" + upstreamUserId + "/status?status=" + status, null, new TypeReference<>() {});
    }

    // ===== Upstream Route =====

    public List<ClientAppUpstreamRouteDTO> listUpstreamRoutes(String clientAppId) {
        return http.get("/api/v1/business-agent/client-apps/" + clientAppId + "/upstream-routes", new TypeReference<>() {});
    }

    public ClientAppUpstreamRouteDTO upsertUpstreamRoute(String clientAppId, String upstreamRef,
                                                         UpsertClientAppUpstreamRouteForm form) {
        return http.put("/api/v1/business-agent/client-apps/" + clientAppId
                + "/upstream-routes/" + urlEncode(upstreamRef), form, new TypeReference<>() {});
    }

    public ClientAppUpstreamRouteDTO updateUpstreamRouteStatus(String clientAppId, String upstreamRef, String status) {
        return http.put("/api/v1/business-agent/client-apps/" + clientAppId
                + "/upstream-routes/" + urlEncode(upstreamRef)
                + "/status?status=" + urlEncode(status), null, new TypeReference<>() {});
    }

    // ===== Business Object & Function =====

    public BusinessObjectDTO createBusinessObject(CreateBusinessObjectForm form) {
        return http.post("/api/v1/business-agent/business-objects", form, new TypeReference<>() {});
    }

    public BusinessObjectDTO getBusinessObject(String objectId) {
        return http.get("/api/v1/business-agent/business-objects/" + objectId, new TypeReference<>() {});
    }

    public BusinessObjectDTO updateBusinessObject(String objectId, UpdateBusinessObjectForm form) {
        return http.put("/api/v1/business-agent/business-objects/" + objectId, form, new TypeReference<>() {});
    }

    public void importBusinessFunctionManifest(ImportBusinessFunctionManifestForm form) {
        http.post("/api/v1/business-agent/functions/import", form, new TypeReference<Void>() {});
    }

    public ClientAppFunctionGrantDTO grantFunctionToClientApp(String clientAppId, GrantBusinessFunctionForm form) {
        return http.post("/api/v1/business-agent/client-apps/" + clientAppId + "/function-grants", form, new TypeReference<>() {});
    }

    public ClientAppFunctionGrantDTO updateFunctionGrantStatus(String clientAppId, String grantId, String status) {
        return http.put("/api/v1/business-agent/client-apps/" + clientAppId + "/function-grants/" + grantId + "/status?status=" + status, null, new TypeReference<>() {});
    }

    public List<BusinessFunctionSummaryDTO> listClientAppVisibleFunctionSummaries(String clientAppId) {
        return http.get("/api/v1/business-agent/client-apps/" + clientAppId + "/visible-functions", new TypeReference<>() {});
    }

    // ===== Task =====

    public CreatedBusinessAgentTaskDTO createBusinessAgentTask(CreateBusinessAgentTaskForm form) {
        return http.post("/api/v1/business-agent/tasks", form, new TypeReference<>() {});
    }

    public BusinessAgentTaskDTO getBusinessAgentTask(String taskId) {
        return http.get("/api/v1/business-agent/tasks/" + taskId, new TypeReference<>() {});
    }

    public List<BusinessAgentTaskDTO> listTasksBySession(String sessionId) {
        return http.get("/api/v1/business-agent/sessions/" + sessionId + "/tasks", new TypeReference<>() {});
    }

    // ===== Approval =====

    public WorkerGatewayResumeResponseDTO resumeSuspension(String suspendId, WorkerGatewayResumeForm form) {
        return http.post("/api/v1/business-agent/suspensions/" + suspendId + "/resume", form, new TypeReference<>() {});
    }

    private String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private Map<String, String> operatorHeaders(String operatorApiKey) {
        if (operatorApiKey == null || operatorApiKey.isBlank()) {
            return Map.of();
        }
        return Map.of(OPERATOR_KEY_HEADER, operatorApiKey);
    }

    private Map<String, String> clientAppHeaders(String clientAppKey, String clientAppAccessToken) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Client-App-Key", clientAppKey);
        headers.put("X-Client-App-Access-Token", clientAppAccessToken);
        return headers;
    }

    private String frameReportPath(String reportRef, String mode, int maxChars, String clientAppId) {
        if (reportRef == null || reportRef.isBlank()) {
            throw new IllegalArgumentException("reportRef is required");
        }
        StringBuilder path = new StringBuilder("/api/v1/open/frame-reports?reportRef=")
                .append(urlEncode(reportRef));
        if (mode != null && !mode.isBlank()) {
            path.append("&mode=").append(urlEncode(mode));
        }
        if (maxChars > 0) {
            path.append("&maxChars=").append(maxChars);
        }
        if (clientAppId != null && !clientAppId.isBlank()) {
            path.append("&clientAppId=").append(urlEncode(clientAppId));
        }
        return path.toString();
    }

    private String upstreamSystemAgentModelBindingPath(String agentId, String targetTenantId) {
        return upstreamSystemAgentModelBindingPath(agentId, null, targetTenantId);
    }

    private String upstreamSystemAgentModelBindingPath(String agentId, String suffix, String targetTenantId) {
        String path = "/api/v1/upstream-admin/agents/" + urlEncode(agentId) + "/model-bindings";
        if (suffix != null && !suffix.isBlank()) {
            path += "/" + urlEncode(suffix);
        }
        if (targetTenantId != null && !targetTenantId.isBlank()) {
            path += "?targetTenantId=" + urlEncode(targetTenantId);
        }
        return path;
    }

    private String upstreamSystemAgentWorkspaceBindingPath(String agentId, String targetTenantId) {
        return upstreamSystemAgentWorkspaceBindingPath(agentId, null, targetTenantId);
    }

    private String upstreamSystemAgentWorkspaceBindingPath(String agentId, String suffix, String targetTenantId) {
        String path = "/api/v1/upstream-admin/agents/" + urlEncode(agentId) + "/workspace-bindings";
        if (suffix != null && !suffix.isBlank()) {
            path += "/" + urlEncode(suffix);
        }
        if (targetTenantId != null && !targetTenantId.isBlank()) {
            path += "?targetTenantId=" + urlEncode(targetTenantId);
        }
        return path;
    }

    private String upstreamSystemAgentWorkerBindingPath(String agentId, String targetTenantId) {
        return upstreamSystemAgentWorkerBindingPath(agentId, null, targetTenantId);
    }

    private String upstreamSystemAgentWorkerBindingPath(String agentId, String suffix, String targetTenantId) {
        String path = "/api/v1/upstream-admin/agents/" + urlEncode(agentId) + "/worker-bindings";
        if (suffix != null && !suffix.isBlank()) {
            path += "/" + urlEncode(suffix);
        }
        if (targetTenantId != null && !targetTenantId.isBlank()) {
            path += "?targetTenantId=" + urlEncode(targetTenantId);
        }
        return path;
    }

    private String upstreamSystemAgentPath(String agentId, String targetTenantId) {
        String path = "/api/v1/upstream-admin/agents";
        if (agentId != null && !agentId.isBlank()) {
            path += "/" + urlEncode(agentId);
        }
        if (targetTenantId != null && !targetTenantId.isBlank()) {
            path += "?targetTenantId=" + urlEncode(targetTenantId);
        }
        return path;
    }

}
