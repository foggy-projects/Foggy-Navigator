package com.foggy.navigator.sdk.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.foggy.navigator.sdk.internal.HttpHelper;
import com.foggy.navigator.sdk.model.businessagent.*;

import java.util.List;
import java.util.Map;

/**
 * Foggy Navigator Business Agent 控制面 API
 */
public class BusinessAgentApi {

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

    // ===== Business Agent Bundle =====

    public BusinessAgentBundleDTO syncBusinessAgentBundle(SyncBusinessAgentBundleForm form) {
        return http.post("/api/v1/business-agent/agent-bundles/sync", form, new TypeReference<>() {});
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
}
