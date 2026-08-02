package com.foggy.navigator.claude.worker.controller.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.claude.worker.client.ClaudeWorkerClient;
import com.foggy.navigator.claude.worker.model.dto.*;
import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import com.foggy.navigator.claude.worker.model.form.*;
import com.foggy.navigator.business.agent.model.dto.AgentReadinessDTO;
import com.foggy.navigator.business.agent.model.dto.AccountContextFileDTO;
import com.foggy.navigator.business.agent.model.dto.AccountContextFileTreeDTO;
import com.foggy.navigator.business.agent.model.dto.ClientAppRuntimeAccessTokenDTO;
import com.foggy.navigator.business.agent.model.dto.BusinessAgentSessionListDTO;
import com.foggy.navigator.business.agent.model.dto.BusinessAgentSessionMessagesDTO;
import com.foggy.navigator.business.agent.model.dto.ClientAppControlPlanePrincipal;
import com.foggy.navigator.business.agent.model.dto.ResolvedClientAppCredentialDTO;
import com.foggy.navigator.business.agent.model.dto.RuntimeRequestAuditPageDTO;
import com.foggy.navigator.business.agent.model.dto.SkillBundleDTO;
import com.foggy.navigator.business.agent.model.dto.SkillArtifactSliceDTO;
import com.foggy.navigator.business.agent.model.dto.SkillArtifactTreeDTO;
import com.foggy.navigator.business.agent.model.form.AccountContextFileWriteForm;
import com.foggy.navigator.business.agent.model.form.AgentReadinessPreflightForm;
import com.foggy.navigator.business.agent.model.form.SyncAccountSkillBundleForm;
import com.foggy.navigator.business.agent.service.AccountContextFileService;
import com.foggy.navigator.business.agent.service.A2AgentResourceResolver;
import com.foggy.navigator.business.agent.service.BusinessAgentFrameReportService;
import com.foggy.navigator.business.agent.service.BusinessAgentSessionService;
import com.foggy.navigator.business.agent.service.BusinessAgentTaskService;
import com.foggy.navigator.business.agent.service.ClientAppControlCredentialService;
import com.foggy.navigator.business.agent.service.ClientAppRuntimeCredentialResolver;
import com.foggy.navigator.business.agent.service.RuntimeRequestAuditService;
import com.foggy.navigator.business.agent.service.SkillArtifactService;
import com.foggy.navigator.business.agent.service.SkillRegistryService;
import com.foggy.navigator.claude.worker.repository.CodingAgentRepository;
import com.foggy.navigator.claude.worker.repository.ClaudeWorkerRepository;
import com.foggy.navigator.common.repository.WorkingDirectoryRepository;
import com.foggy.navigator.common.enums.LlmModelCategory;
import com.foggy.navigator.claude.worker.service.*;
import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.a2a.*;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.session.registry.UnifiedAgentResolver;
import com.foggy.navigator.session.service.OpenApiSessionQueryService;
import com.foggy.navigator.session.service.TaskDispatchFacade;
import com.foggy.navigator.spi.agent.A2aAgent;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import com.foggy.navigator.spi.claude.ClaudeWorkerFacade;
import com.foggyframework.core.ex.RX;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Open API Controller — 面向第三方系统的集成接口
 * <p>
 * 提供 Worker 管理、工作目录管理、员工 Provisioning 等能力，
 * 供 TMS 等上游系统通过 API Key 程序化调用。
 */
@RestController
@RequestMapping("/api/v1/open")
@Slf4j
@RequiredArgsConstructor
public class OpenApiController {

    private static final String TASK_DIRECTORY_REQUIRED = "TASK_DIRECTORY_REQUIRED";
    private static final String TASK_DIRECTORY_REQUIRED_MESSAGE =
            TASK_DIRECTORY_REQUIRED + ": directoryId is required for Actor-owned BizWorker task";
    private static final String TOOL_SCOPE_SOURCE_SAFE_SMOKE_NO_RUNTIME = "SAFE_SMOKE_NO_RUNTIME";
    private static final String TOOL_SCOPE_KIND_NO_RUNTIME = "NO_RUNTIME_MODEL_TOOL_SURFACE";
    private static final Set<String> SANITIZED_RUNTIME_ERROR_CODES = Set.of(
            "AUDIT_QUERY_MODE_CONFLICT",
            "AUDIT_RECORD_EXPIRED_OR_NOT_FOUND",
            "CLIENT_REQUEST_ID_ALREADY_USED",
            "CLIENT_REQUEST_ID_INVALID",
            "CLIENT_REQUEST_ID_OPERATION_MISMATCH",
            "CLIENT_REQUEST_ID_REQUIRED",
            "FUNCTION_SCOPE_EXPLICIT_NULL",
            "RUNTIME_AUDIT_BOUNDED_WINDOW_REQUIRED",
            "RUNTIME_AUDIT_CREDENTIAL_REQUIRED",
            "RUNTIME_AUDIT_HANDLE_REQUIRED",
            "RUNTIME_AUDIT_LIMIT_INVALID",
            "RUNTIME_AUDIT_OPERATION_INVALID",
            "RUNTIME_AUDIT_RECORD_NOT_FOUND",
            "RUNTIME_AUDIT_SCOPE_NOT_FOUND",
            "RUNTIME_AUDIT_SINCE_INVALID",
            "RUNTIME_AUDIT_UNTIL_INVALID",
            "RUNTIME_AUDIT_WINDOW_INVALID",
            "RUNTIME_AUDIT_WINDOW_TOO_LARGE",
            "RUNTIME_BINDING_AUDIT_AGENT_MISMATCH",
            "RUNTIME_BINDING_AUDIT_AGENT_REQUIRED",
            "RUNTIME_BINDING_AUDIT_DIRECTORY_MISMATCH",
            "RUNTIME_BINDING_AUDIT_DIRECTORY_REQUIRED",
            "RUNTIME_BINDING_AUDIT_MODEL_MISMATCH",
            "RUNTIME_BINDING_AUDIT_MODEL_REQUIRED",
            "RUNTIME_BINDING_AUDIT_NOT_FOUND",
            "RUNTIME_BINDING_AUDIT_UPSTREAM_USER_REQUIRED",
            "RUNTIME_BINDING_AUDIT_WORKER_MISMATCH",
            "RUNTIME_BINDING_AUDIT_WORKER_NOT_FOUND",
            "RUNTIME_CLIENT_APP_CREDENTIAL_REQUIRED",
            "RUNTIME_CLIENT_APP_KEY_REQUIRED",
            "RUNTIME_CLIENT_APP_KEY_UNKNOWN",
            "RUNTIME_CLIENT_APP_SCOPE_UNKNOWN",
            "RUNTIME_STATE_AUDIT_CREDENTIAL_LANE_REJECTED",
            "RUNTIME_STATE_AUDIT_SERVICE_UNAVAILABLE",
            "RUNTIME_TASK_AUDIT_FORBIDDEN",
            "RUNTIME_TASK_AUDIT_NOT_FOUND",
            "RUNTIME_TASK_AUDIT_TASK_REQUIRED",
            "RUNTIME_TASK_AUDIT_UPSTREAM_USER_REQUIRED",
            "RUNTIME_TASK_FORBIDDEN",
            "RUNTIME_TASK_NOT_FOUND",
            "RUNTIME_TASK_REQUIRED",
            "RUNTIME_TASK_UPSTREAM_USER_REQUIRED",
            "RUNTIME_TASK_PROVIDER_UNSUPPORTED",
            "RUNTIME_COMPLETION_READINESS_UNSUPPORTED",
            "RUNTIME_TASK_TERMINATION_FORBIDDEN",
            "RUNTIME_TASK_TERMINATION_BLOCKED",
            "RUNTIME_TASK_RECONCILE_FORBIDDEN",
            "RUNTIME_TASK_RECONCILE_NOT_CANCEL_REQUESTED",
            "RUNTIME_TASK_RECONCILE_EVIDENCE_INSUFFICIENT",
            "RUNTIME_TASK_RECONCILE_EVIDENCE_UNREACHABLE",
            "RUNTIME_TASK_TERMINAL_CLEANUP_REPAIR_BODY_REQUIRED",
            "RUNTIME_TASK_TERMINAL_CLEANUP_REPAIR_DRY_RUN_REQUIRED",
            "RUNTIME_TASK_TERMINAL_CLEANUP_REPAIR_NOT_READY",
            "RUNTIME_TASK_TERMINAL_CLEANUP_REPAIR_REPLAY_PROHIBITED",
            "RUNTIME_TASK_TERMINAL_CLEANUP_REPAIR_ALREADY_COMPLETE",
            "RUNTIME_TASK_TERMINAL_CLEANUP_REPAIR_SERVICE_UNAVAILABLE",
            "NAVIGATOR_TERMINAL_REPUBLISH_READY",
            "EXPECTED_PHYSICAL_WORKER_MISMATCH",
            "EXPECTED_DISPATCH_COUNT_MISMATCH",
            "CONFIRM_TASK_ID_MISMATCH",
            "WORKER_UNREACHABLE",
            "WORKER_TASK_STATUS_UNREACHABLE",
            "WORKER_TERMINATION_NOT_READY",
            "WORKER_ACTIVE_TASK_NOT_PRESENT",
            "SAFE_SMOKE_BODY_REQUIRED",
            "SAFE_SMOKE_FUNCTION_SCOPE_REQUIRED",
            "SAFE_SMOKE_MAX_TURNS_MUST_BE_ONE",
            "SAFE_SMOKE_MESSAGE_REQUIRED",
            "SAFE_SMOKE_REQUIRES_EMPTY_FUNCTION_SCOPE",
            "SAFE_SMOKE_REQUIRES_EMPTY_TOOL_SCOPE",
            "SAFE_SMOKE_RUNTIME_INPUT_NOT_ALLOWED",
            "SAFE_SMOKE_TOKEN_SERVICE_UNAVAILABLE",
            "SAFE_SMOKE_TOOL_SCOPE_REQUIRED",
            "SAFE_SMOKE_UPSTREAM_USER_REQUIRED",
            "TOOL_SCOPE_EXPLICIT_NULL");
    private final OpenApiProvisioningService provisioningService;
    private final ClaudeWorkerService workerService;
    private final ClaudeTaskService claudeTaskService;
    private final WorkingDirectoryService directoryService;
    private final ClaudeWorkerFacade claudeWorkerFacade;
    private final ClaudeWorkerRepository workerRepository;
    private final CodingAgentRepository codingAgentRepository;
    private final WorkingDirectoryRepository directoryRepository;
    private final WorkerHealthChecker healthChecker;
    private final UnifiedAgentResolver agentResolver;
    private final TaskDispatchFacade taskDispatchFacade;
    private final TaskStateReconciler reconciler;
    private final OpenApiSessionQueryService sessionQueryService;
    private final ObjectMapper objectMapper;
    private final OpenApiAgentRouteService agentRouteService;
    private final ObjectProvider<ClientAppRuntimeCredentialResolver> clientAppCredentialResolver;
    private final ObjectProvider<RuntimeRequestAuditService> runtimeRequestAuditService;
    private final ObjectProvider<RuntimeStateAuditService> runtimeStateAuditService;
    private final ObjectProvider<RuntimeTaskClosureService> runtimeTaskClosureService;
    private final ObjectProvider<RuntimeTaskCompletionReadinessService>
            runtimeTaskCompletionReadinessService;
    /** Optional while mixed-version launcher/module candidates are assembled. */
    @Autowired(required = false)
    private RuntimeTaskTerminalCleanupRepairService runtimeTaskTerminalCleanupRepairService;
    private final ObjectProvider<BusinessAgentTaskService> businessAgentTaskService;
    private final ObjectProvider<OpenApiAgentReadinessService> agentReadinessService;
    private final ObjectProvider<SkillArtifactService> skillArtifactService;
    private final ObjectProvider<SkillRegistryService> skillRegistryService;
    private final ObjectProvider<AccountContextFileService> accountContextFileService;
    private final ObjectProvider<BusinessAgentSessionService> businessAgentSessionService;
    private final ObjectProvider<BusinessAgentFrameReportService> businessAgentFrameReportService;
    private final ObjectProvider<ClientAppControlCredentialService> clientAppControlCredentialService;
    private final ObjectProvider<A2AgentResourceResolver> a2AgentResourceResolver;
    private final OpenApiRuntimeTaskLaunchPlanner runtimeTaskLaunchPlanner;
    private final OpenApiRuntimeTaskCreateFacade runtimeTaskCreateFacade;
    private final OpenApiDurableTaskSessionQueryFacade durableTaskSessionQueryFacade;
    private final OpenApiSessionProjectionMapper sessionProjectionMapper;
    private final OpenApiTaskProjectionMapper taskProjectionMapper = new OpenApiTaskProjectionMapper();

    // ===== 1. 自助注册（无需认证） =====

    /**
     * 第三方系统自助注册
     * 创建租户 + 管理员用户 + API Key，返回凭证
     */
    @PostMapping("/register")
    public RX<OpenApiRegisterResultDTO> register(@RequestBody OpenApiRegisterForm form) {
        try {
            OpenApiRegisterResultDTO result = provisioningService.register(form);
            return RX.ok(result);
        } catch (IllegalArgumentException e) {
            return RX.failB(e.getMessage());
        } catch (Exception e) {
            log.error("Open API registration failed: {}", e.getMessage(), e);
            return RX.failA("Registration failed: " + e.getMessage());
        }
    }

    // ===== 2. Worker 管理（需 X-API-Key + TENANT_ADMIN） =====

    @PostMapping("/workers")
    @RequireAuth(roles = {"TENANT_ADMIN"})
    public RX<WorkerDTO> registerWorker(@RequestBody RegisterWorkerForm form) {
        String userId = UserContext.getCurrentUserId();
        String tenantId = UserContext.getCurrentTenantId();
        WorkerDTO dto = workerService.registerWorker(userId, tenantId, form);

        // 注册后健康检查
        try {
            healthChecker.checkWorker(workerService.getWorkerEntity(dto.getWorkerId()));
        } catch (Exception e) {
            log.warn("Initial health check failed for worker {}: {}", dto.getWorkerId(), e.getMessage());
        }

        return RX.ok(workerService.getWorker(userId, dto.getWorkerId()));
    }

    @GetMapping("/workers")
    @RequireAuth(roles = {"TENANT_ADMIN"})
    public RX<List<WorkerDTO>> listWorkers() {
        String tenantId = UserContext.getCurrentTenantId();
        List<WorkerDTO> workers = workerRepository.findByTenantId(tenantId).stream()
                .map(entity -> workerService.getWorker(entity.getUserId(), entity.getWorkerId()))
                .toList();
        return RX.ok(workers);
    }

    @GetMapping("/workers/{workerId}")
    @RequireAuth(roles = {"TENANT_ADMIN"})
    public RX<WorkerDTO> getWorker(@PathVariable String workerId) {
        String tenantId = UserContext.getCurrentTenantId();
        ClaudeWorkerEntity entity = workerRepository.findByWorkerId(workerId)
                .orElseThrow(() -> RX.throwB("Worker not found: " + workerId));
        if (!tenantId.equals(entity.getTenantId())) {
            throw RX.throwB("Worker not found: " + workerId);
        }
        return RX.ok(workerService.getWorker(entity.getUserId(), workerId));
    }

    @PutMapping("/workers/{workerId}")
    @RequireAuth(roles = {"TENANT_ADMIN"})
    public RX<WorkerDTO> updateWorker(@PathVariable String workerId, @RequestBody UpdateWorkerForm form) {
        String tenantId = UserContext.getCurrentTenantId();
        ClaudeWorkerEntity entity = workerRepository.findByWorkerId(workerId)
                .orElseThrow(() -> RX.throwB("Worker not found: " + workerId));
        if (!tenantId.equals(entity.getTenantId())) {
            throw RX.throwB("Worker not found: " + workerId);
        }
        return RX.ok(workerService.updateWorker(entity.getUserId(), workerId, form));
    }

    @DeleteMapping("/workers/{workerId}")
    @RequireAuth(roles = {"TENANT_ADMIN"})
    public RX<Void> deleteWorker(@PathVariable String workerId) {
        String tenantId = UserContext.getCurrentTenantId();
        ClaudeWorkerEntity entity = workerRepository.findByWorkerId(workerId)
                .orElseThrow(() -> RX.throwB("Worker not found: " + workerId));
        if (!tenantId.equals(entity.getTenantId())) {
            throw RX.throwB("Worker not found: " + workerId);
        }
        workerService.deleteWorker(entity.getUserId(), workerId);
        return RX.ok(null);
    }

    @PostMapping("/workers/{workerId}/health-check")
    @RequireAuth(roles = {"TENANT_ADMIN"})
    public RX<WorkerDTO> healthCheck(@PathVariable String workerId) {
        String tenantId = UserContext.getCurrentTenantId();
        ClaudeWorkerEntity entity = workerRepository.findByWorkerId(workerId)
                .orElseThrow(() -> RX.throwB("Worker not found: " + workerId));
        if (!tenantId.equals(entity.getTenantId())) {
            throw RX.throwB("Worker not found: " + workerId);
        }
        healthChecker.checkWorker(entity);
        return RX.ok(workerService.getWorker(entity.getUserId(), workerId));
    }

    // ===== 3. 工作目录管理 =====

    @PostMapping("/directories/init")
    @RequireAuth(roles = {"TENANT_ADMIN"})
    public RX<WorkingDirectoryDTO> initDirectory(@RequestBody InitDirectoryOpenForm form) {
        return legacyDirectoryApiRemoved();
    }

    @GetMapping("/directories")
    @RequireAuth(roles = {"TENANT_ADMIN"})
    public RX<List<WorkingDirectoryDTO>> listDirectories(
            @RequestParam(required = false) String workerId) {
        return legacyDirectoryApiRemoved();
    }

    @GetMapping("/directories/{directoryId}")
    @RequireAuth(roles = {"TENANT_ADMIN"})
    public RX<WorkingDirectoryDTO> getDirectory(@PathVariable String directoryId) {
        return legacyDirectoryApiRemoved();
    }

    @DeleteMapping("/directories/{directoryId}")
    @RequireAuth(roles = {"TENANT_ADMIN"})
    public RX<Void> deleteDirectory(@PathVariable String directoryId) {
        return legacyDirectoryApiRemoved();
    }

    /**
     * 更新目录的自定义环境变量
     * <p>
     * 这些变量会在每次 Claude CLI 执行时注入到进程环境中，
     * 适合注入上游系统 Token、API 地址等配置。
     * 传入完整 Map 覆盖，传空 Map 清除。
     */
    @PutMapping("/directories/{directoryId}/env")
    @RequireAuth(roles = {"TENANT_ADMIN"})
    public RX<Map<String, String>> updateDirectoryEnvVars(
            @PathVariable String directoryId,
            @RequestBody Map<String, String> envVars) {
        return legacyDirectoryApiRemoved();
    }

    /**
     * 更新目录中的文件（覆盖写入）
     * <p>
     * 支持更新 CLAUDE.md、.agents/skills/ 等文件。
     * 文件路径为相对于工作目录的相对路径。
     */
    @PutMapping("/directories/{directoryId}/files")
    @RequireAuth(roles = {"TENANT_ADMIN"})
    public RX<Map<String, Object>> updateDirectoryFiles(
            @PathVariable String directoryId,
            @RequestBody Map<String, String> files) {
        return legacyDirectoryApiRemoved();
    }

    private <T> RX<T> legacyDirectoryApiRemoved() {
        return RX.failB("LEGACY_API_REMOVED: /api/v1/open/directories/* has been removed. "
                + "Use /api/v1/upstream-admin/directories with X-Navi-Admin-Key, "
                + "or ClientApp workspace APIs for owner-aware runtime directories.");
    }

    // ===== 4. 员工 Provisioning =====

    @PostMapping("/provision/employee")
    @RequireAuth(roles = {"TENANT_ADMIN"})
    public RX<ProvisionResultDTO> provisionEmployee(@RequestBody ProvisionEmployeeForm form) {
        String tenantId = UserContext.getCurrentTenantId();
        try {
            ProvisionResultDTO result = provisioningService.provisionEmployee(tenantId, form);
            return RX.ok(result);
        } catch (IllegalArgumentException e) {
            return RX.failB(e.getMessage());
        } catch (Exception e) {
            log.error("Employee provisioning failed: {}", e.getMessage(), e);
            return RX.failA("Provisioning failed: " + e.getMessage());
        }
    }

    // ===== 5. 外部用户管理 =====

    @GetMapping("/users")
    @RequireAuth(roles = {"TENANT_ADMIN"})
    public RX<List<ExternalUserDTO>> listExternalUsers() {
        String tenantId = UserContext.getCurrentTenantId();
        return RX.ok(provisioningService.listExternalUsers(tenantId));
    }

    @GetMapping("/users/{externalUserId}")
    @RequireAuth(roles = {"TENANT_ADMIN"})
    public RX<ExternalUserDTO> getExternalUser(@PathVariable String externalUserId) {
        String tenantId = UserContext.getCurrentTenantId();
        return provisioningService.getExternalUser(tenantId, externalUserId)
                .map(RX::ok)
                .orElseThrow(() -> RX.throwB("External user not found: " + externalUserId));
    }

    @DeleteMapping("/users/{externalUserId}")
    @RequireAuth(roles = {"TENANT_ADMIN"})
    public RX<Void> deleteExternalUser(@PathVariable String externalUserId) {
        String tenantId = UserContext.getCurrentTenantId();
        try {
            provisioningService.deleteExternalUserMapping(tenantId, externalUserId);
            return RX.ok(null);
        } catch (IllegalArgumentException e) {
            return RX.failB(e.getMessage());
        }
    }

    // ===== 6. Agent 查询（A2A 协议） =====

    /**
     * 列出租户下所有 A2A Agent
     */
    @GetMapping("/agents")
    @RequireAuth(roles = {"TENANT_ADMIN"})
    public RX<List<A2aAgentCard>> listAgents() {
        String tenantId = UserContext.getCurrentTenantId();
        AgentResolveContext ctx = AgentResolveContext.builder()
                .tenantId(tenantId)
                .requestSource("OPEN_API")
                .build();
        return RX.ok(agentResolver.listAgents(ctx));
    }

    /**
     * 获取 Agent Card 详情
     */
    @GetMapping("/agents/{agentId}")
    @RequireAuth(roles = {"TENANT_ADMIN", "DEVELOPER"})
    public RX<A2aAgentCard> getAgentCard(@PathVariable String agentId) {
        String tenantId = UserContext.getCurrentTenantId();
        AgentResolveContext ctx = AgentResolveContext.builder()
                .tenantId(tenantId).requestSource("OPEN_API").build();
        A2aAgent agent = agentResolver.resolveAgent(agentId, ctx)
                .orElseThrow(() -> RX.throwB("Agent not found: " + agentId));
        return RX.ok(agent.getAgentCard());
    }

    /**
     * 使用 ClientApp runtime credential 换取短期访问 token。
     * <p>
     * 后续 ask 请求只应携带 X-Client-App-Key + X-Client-App-Access-Token。
     */
    @PostMapping("/client-apps/runtime-token")
    public RX<ClientAppRuntimeAccessTokenDTO> issueClientAppRuntimeToken(HttpServletRequest request) {
        ClientAppRuntimeCredentialResolver resolver = clientAppCredentialResolver.getIfAvailable();
        if (resolver == null) {
            return RX.failB("client app runtime credential resolver is not available");
        }

        String appKey = firstHeader(request,
                "X-Client-App-Key",
                "X-App-Key",
                "X-Foggy-App-Key");
        String appSecret = firstHeader(request,
                "X-Client-App-Secret",
                "X-App-Secret",
                "X-Foggy-App-Secret");
        String clientRequestId = firstHeader(request, "X-Navigator-Client-Request-Id");
        RuntimeRequestAuditService auditService = runtimeRequestAuditService.getIfAvailable();
        RuntimeRequestAuditService.AuditHandle audit = null;
        if (StringUtils.hasText(clientRequestId) && auditService == null) {
            return RX.failB("RUNTIME_AUDIT_SERVICE_UNAVAILABLE");
        }
        if (StringUtils.hasText(clientRequestId)) {
            try {
                audit = auditService.beginRuntimeToken(
                        clientRequestId,
                        firstHeader(request, "X-Navigator-Runtime-Operation"),
                        appKey,
                        firstHeader(request, "X-Navigator-Agent-Code"),
                        firstHeader(request,
                                "X-Upstream-User-Id",
                                "X-Foggy-Upstream-User-Id",
                                "X-Client-Upstream-User-Id"));
            } catch (RuntimeException e) {
                return RX.failB(runtimeAuditErrorCode(e, "RUNTIME_AUDIT_RECORDING_FAILED"));
            }
        }
        ClientAppRuntimeAccessTokenDTO token;
        try {
            token = resolver.issueAccessToken(appKey, appSecret, Duration.ofMinutes(30), clientRequestId);
        } catch (RuntimeException e) {
            String code = runtimeCredentialErrorCode(e);
            if (audit != null) {
                try {
                    auditService.runtimeTokenRejected(audit, code);
                } catch (RuntimeException ignored) {
                    // The credential rejection remains authoritative; never expose audit persistence details.
                }
            }
            return RX.failB(code);
        }
        if (audit != null) {
            try {
                auditService.runtimeTokenIssued(audit);
            } catch (RuntimeException e) {
                return RX.failB("RUNTIME_AUDIT_RECORDING_FAILED");
            }
        }
        return RX.ok(token);
    }

    /**
     * Strictly read-only ClientApp runtime self-audit. Scope is derived from the supplied
     * runtime key/secret and cannot be overridden by request parameters.
     */
    @GetMapping("/runtime-audits")
    public RX<RuntimeRequestAuditPageDTO> queryRuntimeAudits(
            @RequestParam(required = false) String requestId,
            @RequestParam(required = false) String since,
            @RequestParam(required = false) String until,
            @RequestParam(required = false) String operation,
            @RequestParam(required = false) String agentCode,
            @RequestParam(required = false) String upstreamUserId,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        RuntimeRequestAuditService auditService = runtimeRequestAuditService.getIfAvailable();
        if (auditService == null) {
            return RX.failB("RUNTIME_AUDIT_SERVICE_UNAVAILABLE");
        }
        if (hasForbiddenRuntimeAuditCredential(request)) {
            return RX.failB("RUNTIME_AUDIT_CREDENTIAL_LANE_REJECTED");
        }
        String appKey = firstHeader(request,
                "X-Client-App-Key",
                "X-App-Key",
                "X-Foggy-App-Key");
        String appSecret = firstHeader(request,
                "X-Client-App-Secret",
                "X-App-Secret",
                "X-Foggy-App-Secret");
        try {
            return RX.ok(auditService.querySelfAudit(
                    appKey,
                    appSecret,
                    requestId,
                    parseAuditInstant(since, "RUNTIME_AUDIT_SINCE_INVALID"),
                    parseAuditInstant(until, "RUNTIME_AUDIT_UNTIL_INVALID"),
                    operation,
                    agentCode,
                    upstreamUserId,
                    limit));
        } catch (RuntimeException e) {
            return RX.failB(runtimeAuditErrorCode(e, "RUNTIME_AUDIT_QUERY_FAILED"));
        }
    }

    /**
     * Strictly read-only projection of the currently durable ClientApp runtime binding.
     */
    @GetMapping("/runtime/binding-audit")
    public RX<RuntimeBindingAuditDTO> auditRuntimeBinding(
            @RequestParam String agentCode,
            @RequestParam String upstreamUserId,
            @RequestParam String modelConfigId,
            @RequestParam String directoryId,
            HttpServletRequest request) {
        RuntimeStateAuditService auditService = runtimeStateAuditService.getIfAvailable();
        if (auditService == null) {
            return RX.failB("RUNTIME_STATE_AUDIT_SERVICE_UNAVAILABLE");
        }
        if (hasForbiddenRuntimeStateAuditCredential(request)) {
            return RX.failB("RUNTIME_STATE_AUDIT_CREDENTIAL_LANE_REJECTED");
        }
        try {
            return RX.ok(auditService.auditBinding(
                    runtimeAuditAppKey(request),
                    runtimeAuditAppSecret(request),
                    agentCode,
                    upstreamUserId,
                    modelConfigId,
                    directoryId));
        } catch (RuntimeException e) {
            return RX.failB(runtimeAuditErrorCode(e, "RUNTIME_BINDING_AUDIT_QUERY_FAILED"));
        }
    }

    /**
     * Strictly read-only projection of one existing durable task and its terminal capability state.
     */
    @GetMapping("/runtime/task-audit")
    public RX<RuntimeTaskAuditDTO> auditRuntimeTask(
            @RequestParam String taskId,
            HttpServletRequest request) {
        RuntimeStateAuditService auditService = runtimeStateAuditService.getIfAvailable();
        if (auditService == null) {
            return RX.failB("RUNTIME_STATE_AUDIT_SERVICE_UNAVAILABLE");
        }
        if (hasForbiddenRuntimeStateAuditCredential(request)) {
            return RX.failB("RUNTIME_STATE_AUDIT_CREDENTIAL_LANE_REJECTED");
        }
        try {
            return RX.ok(auditService.auditTask(
                    runtimeAuditAppKey(request),
                    runtimeAuditAppSecret(request),
                    firstHeader(request,
                            "X-Upstream-User-Id",
                            "X-Foggy-Upstream-User-Id",
                            "X-Client-Upstream-User-Id"),
                    taskId));
        } catch (RuntimeException e) {
            return RX.failB(runtimeAuditErrorCode(e, "RUNTIME_TASK_AUDIT_QUERY_FAILED"));
        }
    }

    @GetMapping("/runtime/termination-readiness")
    public RX<RuntimeTerminationReadinessDTO> runtimeTerminationReadiness(
            @RequestParam String taskId,
            @RequestParam(required = false) String expectedPhysicalWorkerId,
            HttpServletRequest request) {
        RuntimeTaskClosureService service = runtimeTaskClosureService.getIfAvailable();
        if (service == null) return RX.failB("RUNTIME_TASK_CLOSURE_SERVICE_UNAVAILABLE");
        if (hasForbiddenRuntimeStateAuditCredential(request)) {
            return RX.failB("RUNTIME_STATE_AUDIT_CREDENTIAL_LANE_REJECTED");
        }
        try {
            return RX.ok(service.readiness(
                    runtimeAuditAppKey(request), runtimeAuditAppSecret(request),
                    runtimeUpstreamUserId(request), taskId, expectedPhysicalWorkerId));
        } catch (RuntimeException e) {
            return RX.failB(runtimeAuditErrorCode(e, "RUNTIME_TERMINATION_READINESS_FAILED"));
        }
    }

    /**
     * Strictly read-only completion evidence and provider/process observation
     * for one existing runtime-owned task.
     */
    @GetMapping("/runtime/task-completion-readiness")
    public RX<RuntimeTaskCompletionReadinessDTO> runtimeTaskCompletionReadiness(
            @RequestParam String taskId,
            @RequestParam String expectedPhysicalWorkerId,
            HttpServletRequest request) {
        RuntimeTaskCompletionReadinessService service =
                runtimeTaskCompletionReadinessService.getIfAvailable();
        if (service == null) {
            return RX.failB("RUNTIME_COMPLETION_READINESS_SERVICE_UNAVAILABLE");
        }
        if (hasForbiddenRuntimeStateAuditCredential(request)) {
            return RX.failB("RUNTIME_STATE_AUDIT_CREDENTIAL_LANE_REJECTED");
        }
        try {
            return RX.ok(service.inspect(
                    runtimeAuditAppKey(request), runtimeAuditAppSecret(request),
                    runtimeUpstreamUserId(request), taskId, expectedPhysicalWorkerId));
        } catch (RuntimeException e) {
            return RX.failB(runtimeAuditErrorCode(
                    e, "RUNTIME_TASK_COMPLETION_READINESS_FAILED"));
        }
    }

    @PostMapping("/runtime/task-terminate")
    public RX<RuntimeTaskClosureDTO> runtimeTaskTerminate(
            @RequestBody RuntimeTaskTerminateForm form,
            HttpServletRequest request) {
        RuntimeTaskClosureService service = runtimeTaskClosureService.getIfAvailable();
        if (service == null) return RX.failB("RUNTIME_TASK_CLOSURE_SERVICE_UNAVAILABLE");
        if (hasForbiddenRuntimeStateAuditCredential(request)) {
            return RX.failB("RUNTIME_STATE_AUDIT_CREDENTIAL_LANE_REJECTED");
        }
        if (form == null) return RX.failB("RUNTIME_TASK_TERMINATE_BODY_REQUIRED");
        try {
            return RX.ok(service.terminate(
                    runtimeAuditAppKey(request), runtimeAuditAppSecret(request),
                    runtimeUpstreamUserId(request),
                    firstHeader(request, "X-Navigator-Client-Request-Id"),
                    form.getTaskId(), form.getExpectedPhysicalWorkerId(), form.getReason(),
                    form.getConfirmTaskId(), Boolean.TRUE.equals(form.getDryRun())));
        } catch (RuntimeException e) {
            return RX.failB(runtimeAuditErrorCode(e, "RUNTIME_TASK_TERMINATE_FAILED"));
        }
    }

    @PostMapping("/runtime/task-reconcile")
    public RX<RuntimeTaskClosureDTO> runtimeTaskReconcile(
            @RequestBody RuntimeTaskReconcileForm form,
            HttpServletRequest request) {
        RuntimeTaskClosureService service = runtimeTaskClosureService.getIfAvailable();
        if (service == null) return RX.failB("RUNTIME_TASK_CLOSURE_SERVICE_UNAVAILABLE");
        if (hasForbiddenRuntimeStateAuditCredential(request)) {
            return RX.failB("RUNTIME_STATE_AUDIT_CREDENTIAL_LANE_REJECTED");
        }
        if (form == null) {
            return RX.failB("RUNTIME_TASK_RECONCILE_BODY_REQUIRED");
        }
        try {
            String clientRequestId =
                    firstHeader(request, "X-Navigator-Client-Request-Id");
            if (!form.isLegacyProjectionRepairRequest()) {
                return RX.ok(service.reconcileTerminationRequest(
                        runtimeAuditAppKey(request), runtimeAuditAppSecret(request),
                        runtimeUpstreamUserId(request),
                        clientRequestId, form.getTaskId()));
            }
            if (form.getExpectedDispatchCount() == null) {
                return RX.failB("RUNTIME_TASK_RECONCILE_FIELDS_REQUIRED");
            }
            return RX.ok(service.reconcile(
                    runtimeAuditAppKey(request), runtimeAuditAppSecret(request),
                    runtimeUpstreamUserId(request),
                    clientRequestId,
                    form.getTaskId(), form.getExpectedPhysicalWorkerId(),
                    form.getExpectedDispatchCount(), form.getConfirmTaskId(),
                    Boolean.TRUE.equals(form.getDryRun())));
        } catch (RuntimeException e) {
            return RX.failB(runtimeAuditErrorCode(e, "RUNTIME_TASK_RECONCILE_FAILED"));
        }
    }

    /**
     * The only mutation route for an already-terminal task with incomplete
     * durable cleanup. It is distinct from read-only termination-request
     * reconciliation and never delegates to a provider closure service.
     */
    @PostMapping("/runtime/task-terminal-cleanup-repair")
    public RX<RuntimeTaskTerminalCleanupRepairDTO> runtimeTaskTerminalCleanupRepair(
            @RequestBody RuntimeTaskTerminalCleanupRepairForm form,
            HttpServletRequest request) {
        RuntimeTaskTerminalCleanupRepairService service = runtimeTaskTerminalCleanupRepairService;
        if (service == null) {
            return RX.failB("RUNTIME_TASK_TERMINAL_CLEANUP_REPAIR_SERVICE_UNAVAILABLE");
        }
        if (hasForbiddenRuntimeStateAuditCredential(request)) {
            return RX.failB("RUNTIME_STATE_AUDIT_CREDENTIAL_LANE_REJECTED");
        }
        if (form == null) {
            return RX.failB("RUNTIME_TASK_TERMINAL_CLEANUP_REPAIR_BODY_REQUIRED");
        }
        try {
            return RX.ok(service.repair(
                    runtimeAuditAppKey(request), runtimeAuditAppSecret(request),
                    runtimeUpstreamUserId(request),
                    firstHeader(request, "X-Navigator-Client-Request-Id"),
                    form.getTaskId(), form.getExpectedPhysicalWorkerId(),
                    form.getConfirmTaskId(), Boolean.TRUE.equals(form.getDryRun())));
        } catch (RuntimeException e) {
            return RX.failB(runtimeAuditErrorCode(
                    e, "RUNTIME_TASK_TERMINAL_CLEANUP_REPAIR_FAILED"));
        }
    }

    /**
     * 向 Agent 发送查询（异步模式）
     * <p>
     * 立即返回 SUBMITTED 状态的任务，调用者通过轮询端点获取结果。
     * 支持多轮会话：首次不传 contextId 则平台自动生成；
     * 后续传入相同 contextId 可恢复 Claude 会话上下文。
     */
    @PostMapping("/agents/{agentId}/ask")
    public RX<OpenApiTaskDTO> askAgent(
            @PathVariable String agentId,
            @RequestBody OpenApiQueryForm form,
            HttpServletRequest request) {
        String upstreamUserId = firstHeader(request,
                "X-Upstream-User-Id",
                "X-Foggy-Upstream-User-Id",
                "X-Client-Upstream-User-Id");
        RuntimeRequestAuditService requestAuditService = runtimeRequestAuditService.getIfAvailable();
        String clientRequestId = firstHeader(request, "X-Navigator-Client-Request-Id");
        if (!StringUtils.hasText(clientRequestId)) {
            clientRequestId = UUID.randomUUID().toString();
        }
        if (requestAuditService == null) {
            return RX.failB("RUNTIME_AUDIT_SERVICE_UNAVAILABLE");
        }
        RuntimeRequestAuditService.AuditHandle askRequestAudit;
        try {
            askRequestAudit = requestAuditService.beginAskRequest(
                    clientRequestId,
                    firstHeader(request, "X-Navigator-Parent-Client-Request-Id"),
                    firstHeader(request, "X-Client-App-Key", "X-App-Key", "X-Foggy-App-Key"),
                    agentId,
                    upstreamUserId);
        } catch (RuntimeException e) {
            return RX.failB(runtimeAuditErrorCode(e, "RUNTIME_AUDIT_RECORDING_FAILED"));
        }
        ResolvedClientAppCredentialDTO clientAppCredential;
        try {
            clientAppCredential = requireClientAppRuntimeToken(request);
            requestAuditService.authenticationCompleted(askRequestAudit);
        } catch (RuntimeException e) {
            requestAuditService.authenticationFailed(askRequestAudit, "RUNTIME_AUTHENTICATION_FAILED");
            return RX.failB("RUNTIME_AUTHENTICATION_FAILED");
        }
        OpenApiAgentRouteService.ResolvedOpenApiAgentRoute route;
        try {
            route = requireOpenApiAgentRoute(agentId, clientAppCredential);
        } catch (RuntimeException e) {
            requestAuditService.askFailed(askRequestAudit, "AGENT_ROUTE_RESOLUTION_FAILED");
            throw e;
        }
        String tenantId = clientAppCredential.getTenantId();

        if (form == null) {
            requestAuditService.askFailed(askRequestAudit, "STANDARD_ASK_BODY_REQUIRED");
            return RX.failB("request body is required");
        }
        String messageContent = form.resolveMessage();
        if (messageContent == null || messageContent.isBlank()) {
            requestAuditService.askFailed(askRequestAudit, "STANDARD_ASK_MESSAGE_REQUIRED");
            return RX.failB("message is required");
        }

        // 构建 A2aMessage
        boolean requestedContextId = form.getContextId() != null && !form.getContextId().isBlank();
        String contextId = requestedContextId
                ? form.getContextId().trim()
                : BusinessAgentSessionService.generateContextId();
        if (requestedContextId && !StringUtils.hasText(upstreamUserId)) {
            requestAuditService.askFailed(askRequestAudit, "UPSTREAM_USER_ID_REQUIRED");
            return RX.failB("upstream user id is required when contextId is provided");
        }

        A2AgentResourceResolver resourceResolver;
        OpenApiRuntimeTaskLaunchPlanner.ResolvedLaunchResources launchResources;
        try {
            resourceResolver = requireA2AgentResourceResolver();
            launchResources = runtimeTaskLaunchPlanner.resolveResources(
                    resourceResolver,
                    new OpenApiRuntimeTaskLaunchPlanner.LaunchContext(
                            tenantId,
                            clientAppCredential.getClientAppId(),
                            upstreamUserId,
                            route.agentId(),
                            route.skillId(),
                            contextId),
                    form);
        } catch (RuntimeException e) {
            requestAuditService.askFailed(askRequestAudit, "STANDARD_ASK_RESOURCE_RESOLUTION_FAILED");
            throw e;
        }
        if (launchResources.taskDirectoryMissing()) {
            requestAuditService.askFailed(askRequestAudit, "TASK_DIRECTORY_REQUIRED");
            return RX.failB(TASK_DIRECTORY_REQUIRED_MESSAGE);
        }
        A2AgentResourceResolver.ResolvedModelResource modelResource =
                launchResources.modelResource();
        String modelConfigId = modelResource.modelConfigId();
        OpenApiRuntimeTaskCreateFacade.PrepareOutcome prepareOutcome = runtimeTaskCreateFacade.prepare(
                new OpenApiRuntimeTaskCreateFacade.VerifiedCreateContext(
                        new OpenApiRuntimeTaskCreateFacade.RuntimeCredentialReference(
                                tenantId,
                                clientAppCredential.getClientAppId(),
                                clientAppCredential.getCredentialId(),
                                clientAppCredential.getRuntimeAccessTokenId()),
                        upstreamUserId,
                        route.agentId(),
                        route.skillId(),
                        contextId,
                        requestedContextId,
                        modelConfigId,
                        askRequestAudit));
        if (!prepareOutcome.ready()) {
            return RX.failB(firstNonBlank(
                    sanitizeDiagnosticText(prepareOutcome.rejectionMessage()),
                    prepareOutcome.rejectionFallback(),
                    "open api request rejected"));
        }
        String clientContextJson = serializeClientContext(form.getClientContext());
        OpenApiRuntimeTaskLaunchPlanner.LaunchPlan launchPlan =
                runtimeTaskLaunchPlanner.plan(resourceResolver, launchResources, form);
        Map<String, Object> metadata = launchPlan.mutableMetadata();
        if (askRequestAudit != null) {
            try {
                requestAuditService.taskAdmissionRecorded(
                        askRequestAudit,
                        requestAuditEvidence(
                                null, "ADMITTED", false, null, route.agentId(), upstreamUserId,
                                stringValue(metadata.get("workerId")), modelConfigId, modelResource.modelName(),
                                metadata, "STANDARD_SCOPE_ADMITTED", false));
            } catch (RuntimeException e) {
                return RX.failB("RUNTIME_AUDIT_RECORDING_FAILED");
            }
        }
        OpenApiRuntimeTaskCreateFacade.CreateOutcome createOutcome = runtimeTaskCreateFacade.create(
                new OpenApiRuntimeTaskCreateFacade.VerifiedCreateCommand(
                        prepareOutcome.preparedContext(),
                        messageContent,
                        form.getMaxTurns(),
                        clientContextJson,
                        launchPlan));
        if (!createOutcome.created()) {
            return RX.failB(firstNonBlank(
                    sanitizeDiagnosticText(createOutcome.rejectionMessage()),
                    createOutcome.rejectionFallback(),
                    "open api request rejected"));
        }
        A2aTask task = createOutcome.task();
        metadata = createOutcome.metadata();

        SessionTaskEntity taskEntity = sessionQueryService.findTask(task.getId()).orElse(null);
        OpenApiTaskDTO response = toOpenApiTaskDTO(task, route.agentId(), taskEntity);
        response.setClientRequestId(clientRequestId);
        applyScopeDiagnostics(response, metadata);
        return RX.ok(response);
    }

    /**
     * Terminal safe-smoke endpoint. It validates explicit empty scopes, creates and immediately
     * revokes an empty-function task token, and never submits a task to a Worker or model runtime.
     */
    @PostMapping("/agents/{agentId}/safe-smoke")
    public RX<OpenApiTaskDTO> safeSmokeAgent(
            @PathVariable String agentId,
            @RequestBody OpenApiQueryForm form,
            HttpServletRequest request) {
        String clientRequestId = firstHeader(request, "X-Navigator-Client-Request-Id");
        String upstreamUserId = firstHeader(request,
                "X-Upstream-User-Id",
                "X-Foggy-Upstream-User-Id",
                "X-Client-Upstream-User-Id");
        RuntimeRequestAuditService auditService = runtimeRequestAuditService.getIfAvailable();
        ResolvedClientAppCredentialDTO credential;
        try {
            credential = requireClientAppRuntimeToken(request);
        } catch (RuntimeException e) {
            return RX.failB(safeSmokeErrorCode(e));
        }
        RuntimeRequestAuditService.AuditHandle audit = null;
        if (StringUtils.hasText(clientRequestId)) {
            if (auditService == null) {
                return RX.failB("RUNTIME_AUDIT_SERVICE_UNAVAILABLE");
            }
            try {
                audit = auditService.beginSafeSmoke(clientRequestId, credential, agentId, upstreamUserId);
            } catch (RuntimeException e) {
                return RX.failB(runtimeAuditErrorCode(e, "RUNTIME_AUDIT_RECORDING_FAILED"));
            }
        }

        try {
            OpenApiAgentRouteService.ResolvedOpenApiAgentRoute route =
                    requireOpenApiAgentRoute(agentId, credential);
            if (form == null) {
                return failSafeSmoke(auditService, audit, "SAFE_SMOKE_BODY_REQUIRED");
            }
            String message = form.resolveMessage();
            if (!StringUtils.hasText(message)) {
                return failSafeSmoke(auditService, audit, "SAFE_SMOKE_MESSAGE_REQUIRED");
            }
            if (form.getMaxTurns() == null || form.getMaxTurns() != 1) {
                return failSafeSmoke(auditService, audit, "SAFE_SMOKE_MAX_TURNS_MUST_BE_ONE");
            }
            String scopeError = validateSafeSmokeScopes(form);
            if (scopeError != null) {
                return failSafeSmoke(auditService, audit, scopeError);
            }
            if (StringUtils.hasText(form.getSystemPrompt())
                    || StringUtils.hasText(form.getFirstMsg())
                    || (form.getAttachments() != null && !form.getAttachments().isEmpty())) {
                return failSafeSmoke(auditService, audit, "SAFE_SMOKE_RUNTIME_INPUT_NOT_ALLOWED");
            }
            if (!StringUtils.hasText(upstreamUserId)) {
                return failSafeSmoke(auditService, audit, "SAFE_SMOKE_UPSTREAM_USER_REQUIRED");
            }
            String tenantId = credential.getTenantId();
            String contextId = StringUtils.hasText(form.getContextId())
                    ? form.getContextId().trim()
                    : BusinessAgentSessionService.generateContextId();
            A2AgentResourceResolver resourceResolver = requireA2AgentResourceResolver();
            A2AgentResourceResolver.ResolvedAgentResource agentResource = resourceResolver.resolveRequiredAgent(
                    tenantId,
                    credential.getClientAppId(),
                    upstreamUserId,
                    route.agentId());
            String requestedModelConfigId = extractRequestedModelConfigId(form);
            String effectiveRequestedModelConfigId = StringUtils.hasText(requestedModelConfigId)
                    ? requestedModelConfigId
                    : agentResource.defaultModelConfigId();
            A2AgentResourceResolver.ResolvedModelResource modelResource =
                    resourceResolver.resolveRequiredModelForAgent(
                            tenantId,
                            credential.getClientAppId(),
                            agentResource,
                            effectiveRequestedModelConfigId,
                            extractRequestedModelVariant(form),
                            LlmModelCategory.GENERAL);
            BusinessAgentTaskService service = businessAgentTaskService.getIfAvailable();
            if (service == null) {
                return failSafeSmoke(auditService, audit, "SAFE_SMOKE_TOKEN_SERVICE_UNAVAILABLE");
            }
            BusinessAgentTaskService.SafeSmokeResult result = service.performOpenApiSafeSmoke(
                    tenantId,
                    resolveAgentOwnerUserId(route.agentId(), tenantId),
                    credential.getClientAppId(),
                    upstreamUserId,
                    route.skillId(),
                    contextId,
                    modelResource.modelConfigId());
            LocalDateTime now = LocalDateTime.now();
            OpenApiTaskDTO response = OpenApiTaskDTO.builder()
                    .taskId(result.taskId())
                    .agentId(route.agentId())
                    .status("COMPLETED")
                    .contextId(result.sessionId())
                    .modelConfigId(result.modelConfigId())
                    .modelConfigSource(modelResource.source())
                    .workerBackend("NONE")
                    .providerType("NONE")
                    .taskSource("SAFE_SMOKE")
                    .workerSource("NO_WORKER_DISPATCH")
                    .backendSource("SAFE_SMOKE_NO_RUNTIME")
                    .effectiveToolCount(0)
                    .effectiveFunctionCount(result.effectiveFunctionCount())
                    .toolScopeSource(TOOL_SCOPE_SOURCE_SAFE_SMOKE_NO_RUNTIME)
                    .toolScopeKind(TOOL_SCOPE_KIND_NO_RUNTIME)
                    .functionScopeSource(result.functionScopeSource())
                    .taskTokenFunctionScopeEmpty(result.functionScopeEmpty())
                    .runtimeDispatched(false)
                    .taskTokenStatus(result.taskTokenStatus())
                    .result("SAFE_SMOKE_VERIFIED_NO_RUNTIME_DISPATCH")
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            if (audit != null) {
                try {
                    auditService.safeSmokeCompleted(audit, new RuntimeRequestAuditService.SafeSmokeEvidence(
                            response.getTaskId(),
                            response.getStatus(),
                            response.getEffectiveToolCount(),
                            response.getToolScopeKind(),
                            response.getToolScopeSource(),
                            response.getEffectiveFunctionCount(),
                            response.getFunctionScopeSource(),
                            response.getTaskTokenFunctionScopeEmpty(),
                            response.getTaskTokenStatus(),
                            response.getRuntimeDispatched(),
                            response.getResult()));
                } catch (RuntimeException e) {
                    return RX.failB("RUNTIME_AUDIT_RECORDING_FAILED");
                }
            }
            return RX.ok(response);
        } catch (RuntimeException e) {
            String code = safeSmokeErrorCode(e);
            if (audit != null) {
                try {
                    auditService.safeSmokeFailed(audit, code);
                } catch (RuntimeException ignored) {
                    // Preserve the stable safe-smoke failure and do not expose persistence details.
                }
            }
            return RX.failB(code);
        }
    }

    private RX<OpenApiTaskDTO> failSafeSmoke(
            RuntimeRequestAuditService auditService,
            RuntimeRequestAuditService.AuditHandle audit,
            String code) {
        if (audit != null) {
            try {
                auditService.safeSmokeFailed(audit, code);
            } catch (RuntimeException ignored) {
                // Preserve the stable safe-smoke failure and do not expose persistence details.
            }
        }
        return RX.failB(code);
    }

    private String validateSafeSmokeScopes(OpenApiQueryForm form) {
        if (!form.isAllowedToolsProvided()) {
            return "SAFE_SMOKE_TOOL_SCOPE_REQUIRED";
        }
        if (form.getAllowedTools() == null) {
            return "TOOL_SCOPE_EXPLICIT_NULL";
        }
        if (!cleanRequestListPreservingEmpty(form.getAllowedTools()).isEmpty()) {
            return "SAFE_SMOKE_REQUIRES_EMPTY_TOOL_SCOPE";
        }
        if (!form.isAllowedFunctionsProvided()) {
            return "SAFE_SMOKE_FUNCTION_SCOPE_REQUIRED";
        }
        if (form.getAllowedFunctions() == null) {
            return "FUNCTION_SCOPE_EXPLICIT_NULL";
        }
        if (!cleanRequestListPreservingEmpty(form.getAllowedFunctions()).isEmpty()) {
            return "SAFE_SMOKE_REQUIRES_EMPTY_FUNCTION_SCOPE";
        }
        return null;
    }

    private void applyScopeDiagnostics(OpenApiTaskDTO target, Map<String, Object> metadata) {
        if (target == null || metadata == null) {
            return;
        }
        target.setEffectiveToolCount(integerValue(metadata.get("effectiveToolCount")));
        target.setEffectiveFunctionCount(integerValue(metadata.get("effectiveFunctionCount")));
        target.setToolScopeSource(stringValue(metadata.get("toolScopeSource")));
        target.setToolScopeKind(stringValue(metadata.get("toolScopeKind")));
        target.setFunctionScopeSource(stringValue(metadata.get("functionScopeSource")));
        target.setTaskTokenFunctionScopeEmpty(booleanValue(metadata.get("taskTokenFunctionScopeEmpty")));
        target.setRuntimeDispatched(booleanValue(metadata.get("runtimeDispatched")));
        target.setTaskTokenStatus(stringValue(metadata.get("taskTokenStatus")));
    }

    private RuntimeRequestAuditService.TaskEvidence requestAuditEvidence(
            String taskId,
            String status,
            boolean terminal,
            String sanitizedErrorCode,
            String agentCode,
            String upstreamUserId,
            String physicalWorkerId,
            String modelConfigId,
            String modelVariant,
            Map<String, Object> metadata,
            String result,
            boolean dispatched) {
        boolean taskCreated = StringUtils.hasText(taskId);
        return new RuntimeRequestAuditService.TaskEvidence(
                taskId,
                status,
                terminal,
                sanitizedErrorCode,
                agentCode,
                upstreamUserId,
                physicalWorkerId,
                modelConfigId,
                modelVariant,
                integerValue(metadata.get("requestedToolCount")),
                integerValue(metadata.get("effectiveToolCount")),
                stringValue(metadata.get("toolScopeKind")),
                stringValue(metadata.get("toolScopeSource")),
                integerValue(metadata.get("requestedFunctionCount")),
                integerValue(metadata.get("effectiveFunctionCount")),
                stringValue(metadata.get("functionScopeSource")),
                booleanValue(metadata.get("taskTokenFunctionScopeEmpty")),
                taskCreated ? "ACTIVE" : "NOT_ISSUED",
                dispatched,
                dispatched,
                false,
                dispatched ? 1 : 0,
                0,
                0,
                result);
    }

    private String extractRequestedModelConfigId(OpenApiQueryForm form) {
        if (form == null) {
            return null;
        }
        if (StringUtils.hasText(form.getModelConfigId())) {
            return form.getModelConfigId();
        }
        Object value = form.getMetadata() != null ? form.getMetadata().get("modelConfigId") : null;
        return value instanceof String text && StringUtils.hasText(text) ? text : null;
    }

    private String extractRequestedModelVariant(OpenApiQueryForm form) {
        if (form == null) {
            return null;
        }
        if (StringUtils.hasText(form.getModelVariant())) {
            return form.getModelVariant();
        }
        if (form.getMetadata() == null || form.getMetadata().isEmpty()) {
            return null;
        }
        for (String key : List.of("modelVariant", "model", "modelName", "model_name", "model_variant")) {
            Object value = form.getMetadata().get(key);
            if (value instanceof String text && StringUtils.hasText(text)) {
                return text;
            }
        }
        return null;
    }

    private A2AgentResourceResolver requireA2AgentResourceResolver() {
        A2AgentResourceResolver resolver = a2AgentResourceResolver.getIfAvailable();
        if (resolver == null) {
            throw RX.throwB("A2Agent resource resolver is not available");
        }
        return resolver;
    }

    @PostMapping("/agents/{agentId}/preflight")
    public RX<AgentReadinessDTO> preflightAgent(
            @PathVariable String agentId,
            @RequestBody(required = false) AgentReadinessPreflightForm form,
            HttpServletRequest request) {
        OpenApiAgentReadinessService service = agentReadinessService.getIfAvailable();
        if (service == null) {
            return RX.failB("agent readiness service is not available");
        }
        ResolvedClientAppCredentialDTO credential = requireClientAppRuntimeToken(request);
        return RX.ok(service.verify(agentId, form, credential, resolveBaseUrl(request)));
    }

    @GetMapping("/skills/{skillId}/files/tree")
    public RX<SkillArtifactTreeDTO> getSkillArtifactTree(
            @PathVariable String skillId,
            HttpServletRequest request) {
        SkillArtifactService service = skillArtifactService.getIfAvailable();
        if (service == null) {
            return RX.failB("skill artifact service is not available");
        }
        ResolvedClientAppCredentialDTO credential = requireClientAppAccess(skillId, request);
        return RX.ok(service.tree(credential.getTenantId(), credential.getClientAppId(), skillId));
    }

    @GetMapping("/skills/{skillId}/files/slice")
    public RX<SkillArtifactSliceDTO> getSkillArtifactSlice(
            @PathVariable String skillId,
            @RequestParam String path,
            @RequestParam(required = false) Integer startLine,
            @RequestParam(required = false) Integer startColumn,
            @RequestParam(required = false) Integer maxChars,
            HttpServletRequest request) {
        SkillArtifactService service = skillArtifactService.getIfAvailable();
        if (service == null) {
            return RX.failB("skill artifact service is not available");
        }
        ResolvedClientAppCredentialDTO credential = requireClientAppAccess(skillId, request);
        return RX.ok(service.slice(
                credential.getTenantId(),
                credential.getClientAppId(),
                skillId,
                path,
                startLine,
                startColumn,
                maxChars));
    }

    @PostMapping("/accounts/me/skill-bundles/sync")
    public RX<SkillBundleDTO> syncMyAccountSkillBundle(
            @RequestBody SyncAccountSkillBundleForm form,
            HttpServletRequest request) {
        SkillRegistryService service = skillRegistryService.getIfAvailable();
        if (service == null) {
            return RX.failB("skill registry service is not available");
        }
        ResolvedClientAppCredentialDTO credential = requireClientAppRuntimeToken(request);
        String upstreamUserId = firstHeader(request,
                "X-Upstream-User-Id",
                "X-Foggy-Upstream-User-Id",
                "X-Client-Upstream-User-Id");
        if (!StringUtils.hasText(upstreamUserId)) {
            return RX.failB("upstream user id is required");
        }
        return RX.ok(service.syncMyAccountSkillBundle(
                credential.getTenantId(),
                credential.getClientAppId(),
                upstreamUserId,
                form));
    }

    @GetMapping("/accounts/me/context-files")
    public RX<AccountContextFileTreeDTO> listMyAccountContextFiles(HttpServletRequest request) {
        AccountContextFileService service = accountContextFileService.getIfAvailable();
        if (service == null) {
            return RX.failB("account context file service is not available");
        }
        ResolvedClientAppCredentialDTO credential = requireClientAppRuntimeToken(request);
        String upstreamUserId = requireUpstreamUserId(request);
        return RX.ok(service.list(
                credential.getTenantId(),
                credential.getClientAppId(),
                upstreamUserId));
    }

    @GetMapping("/accounts/me/context-files/{fileName}")
    public RX<AccountContextFileDTO> readMyAccountContextFile(
            @PathVariable String fileName,
            HttpServletRequest request) {
        AccountContextFileService service = accountContextFileService.getIfAvailable();
        if (service == null) {
            return RX.failB("account context file service is not available");
        }
        ResolvedClientAppCredentialDTO credential = requireClientAppRuntimeToken(request);
        String upstreamUserId = requireUpstreamUserId(request);
        return RX.ok(service.read(
                credential.getTenantId(),
                credential.getClientAppId(),
                upstreamUserId,
                fileName));
    }

    @PutMapping("/accounts/me/context-files/{fileName}")
    public RX<AccountContextFileDTO> writeMyAccountContextFile(
            @PathVariable String fileName,
            @RequestBody AccountContextFileWriteForm form,
            HttpServletRequest request) {
        AccountContextFileService service = accountContextFileService.getIfAvailable();
        if (service == null) {
            return RX.failB("account context file service is not available");
        }
        ResolvedClientAppCredentialDTO credential = requireClientAppRuntimeToken(request);
        String upstreamUserId = requireUpstreamUserId(request);
        return RX.ok(service.writePolicy(
                credential.getTenantId(),
                credential.getClientAppId(),
                upstreamUserId,
                fileName,
                form));
    }

    @GetMapping("/business-agent/sessions")
    public RX<BusinessAgentSessionListDTO> listMyBusinessAgentSessions(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String cursor,
            HttpServletRequest request) {
        BusinessAgentSessionService service = businessAgentSessionService.getIfAvailable();
        if (service == null) {
            return RX.failB("business agent session service is not available");
        }
        ResolvedClientAppCredentialDTO credential = requireClientAppRuntimeToken(request);
        String upstreamUserId = requireUpstreamUserId(request);
        return RX.ok(service.listSessions(
                credential.getTenantId(),
                credential.getClientAppId(),
                upstreamUserId,
                cursor,
                limit));
    }

    @GetMapping("/business-agent/sessions/{contextId}/messages")
    public RX<BusinessAgentSessionMessagesDTO> getMyBusinessAgentSessionMessages(
            @PathVariable String contextId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "false") boolean includeInternal,
            HttpServletRequest request) {
        BusinessAgentSessionService service = businessAgentSessionService.getIfAvailable();
        if (service == null) {
            return RX.failB("business agent session service is not available");
        }
        ResolvedClientAppCredentialDTO credential = requireClientAppRuntimeToken(request);
        String upstreamUserId = requireUpstreamUserId(request);
        return RX.ok(service.getMessages(
                credential.getTenantId(),
                credential.getClientAppId(),
                upstreamUserId,
                contextId,
                cursor,
                limit,
                includeInternal));
    }

    @GetMapping("/frame-reports")
    public RX<Map<String, Object>> getFrameReport(
            @RequestParam(required = false) String reportRef,
            @RequestParam(required = false) String taskId,
            @RequestParam(required = false) String frameId,
            @RequestParam(required = false) String contextId,
            @RequestParam(required = false) String sessionId,
            @RequestParam(defaultValue = "summary") String mode,
            @RequestParam(required = false) Integer maxChars,
            @RequestParam(required = false) String clientAppId,
            HttpServletRequest request) {
        BusinessAgentFrameReportService service = businessAgentFrameReportService.getIfAvailable();
        if (service == null) {
            return RX.failB("business agent frame report service is not available");
        }
        try {
            ResolvedClientAppCredentialDTO credential = requireClientAppRuntimeOrControlCredential(request, clientAppId);
            return RX.ok(service.getFrameReport(
                    credential.getTenantId(),
                    credential.getClientAppId(),
                    reportRef,
                    taskId,
                    frameId,
                    contextId,
                    sessionId,
                    mode,
                    maxChars));
        } catch (IllegalArgumentException | IllegalStateException | SecurityException e) {
            return RX.failB(e.getMessage());
        }
    }

    private ResolvedClientAppCredentialDTO resolveClientAppCredential(
            String skillId,
            HttpServletRequest request) {
        ClientAppRuntimeCredentialResolver resolver = clientAppCredentialResolver.getIfAvailable();
        if (resolver == null || request == null) {
            return null;
        }

        String appKey = firstHeader(request,
                "X-Client-App-Key",
                "X-App-Key",
                "X-Foggy-App-Key");
        String accessToken = firstHeader(request,
                "X-Client-App-Access-Token",
                "X-App-Access-Token",
                "X-Foggy-App-Access-Token");

        return resolver.resolveAccessTokenForSkill(appKey, accessToken, skillId)
                .orElse(null);
    }

    private ResolvedClientAppCredentialDTO resolveClientAppRuntimeToken(HttpServletRequest request) {
        ClientAppRuntimeCredentialResolver resolver = clientAppCredentialResolver.getIfAvailable();
        if (resolver == null || request == null) {
            return null;
        }

        String appKey = firstHeader(request,
                "X-Client-App-Key",
                "X-App-Key",
                "X-Foggy-App-Key");
        String accessToken = firstHeader(request,
                "X-Client-App-Access-Token",
                "X-App-Access-Token",
                "X-Foggy-App-Access-Token");

        return resolver.resolveAccessToken(appKey, accessToken)
                .orElse(null);
    }

    private ResolvedClientAppCredentialDTO requireClientAppAccess(String skillId, HttpServletRequest request) {
        ResolvedClientAppCredentialDTO resolved = resolveClientAppCredential(skillId, request);
        if (resolved == null) {
            throw RX.throwB("client app access token is required");
        }
        return resolved;
    }

    private ResolvedClientAppCredentialDTO requireClientAppRuntimeToken(HttpServletRequest request) {
        ResolvedClientAppCredentialDTO resolved = resolveClientAppRuntimeToken(request);
        if (resolved == null) {
            throw RX.throwB("client app access token is required");
        }
        return resolved;
    }

    private ResolvedClientAppCredentialDTO requireClientAppRuntimeOrControlCredential(
            HttpServletRequest request,
            String clientAppId) {
        ResolvedClientAppCredentialDTO runtime = resolveClientAppRuntimeToken(request);
        if (runtime != null) {
            return runtime;
        }
        ClientAppControlCredentialService controlService = clientAppControlCredentialService.getIfAvailable();
        if (controlService == null) {
            throw RX.throwB("client app access token is required");
        }
        ClientAppControlPlanePrincipal principal = controlService.requireAccess(
                request,
                ClientAppControlCredentialService.SCOPE_FRAME_REPORT_READ,
                clientAppId);
        return ResolvedClientAppCredentialDTO.builder()
                .credentialId(principal.getCredentialId())
                .tenantId(principal.getTenantId())
                .clientAppId(principal.getClientAppId())
                .build();
    }

    private OpenApiAgentRouteService.ResolvedOpenApiAgentRoute requireOpenApiAgentRoute(
            String routeAgentId,
            ResolvedClientAppCredentialDTO credential) {
        try {
            OpenApiAgentRouteService.ResolvedOpenApiAgentRoute route =
                    agentRouteService.resolve(routeAgentId, credential);
            validateClientAppSkillAccess(credential, route.skillId());
            return route;
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw RX.throwB(e.getMessage());
        }
    }

    private void validateClientAppSkillAccess(
            ResolvedClientAppCredentialDTO credential,
            String skillId) {
        SkillRegistryService service = skillRegistryService.getIfAvailable();
        if (service == null || credential == null || !StringUtils.hasText(skillId)) {
            return;
        }
        service.checkClientAppSkillAccess(
                credential.getTenantId(),
                credential.getClientAppId(),
                skillId);
    }

    private String requireUpstreamUserId(HttpServletRequest request) {
        String upstreamUserId = firstHeader(request,
                "X-Upstream-User-Id",
                "X-Foggy-Upstream-User-Id",
                "X-Client-Upstream-User-Id");
        if (!StringUtils.hasText(upstreamUserId)) {
            throw RX.throwB("upstream user id is required");
        }
        return upstreamUserId;
    }

    private List<String> cleanRequestListPreservingEmpty(List<String> values) {
        if (values == null) {
            return null;
        }
        return values.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
    }

    private String firstHeader(HttpServletRequest request, String... names) {
        if (request == null || names == null) {
            return null;
        }
        for (String name : names) {
            String value = request.getHeader(name);
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private boolean hasForbiddenRuntimeAuditCredential(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        if (hasAnyHeader(request,
                "Authorization",
                "X-API-Key",
                "X-Navigator-API-Key",
                "X-Navi-Admin-Key",
                "X-Navi-Operator-Key",
                "X-Navi-Principal-Credential",
                "X-Client-App-Control-Key",
                "X-Client-App-Access-Token",
                "X-App-Access-Token",
                "X-Foggy-App-Access-Token",
                "X-Task-Token",
                "X-Worker-Token",
                "X-Tenant-Id",
                "X-Platform-Admin-Key",
                "X-System-Admin-Key",
                "X-Operator-Token",
                "X-Principal-Token")) {
            return true;
        }
        return hasAnyNormalizedParameter(request,
                "tenantId",
                "targetTenantId",
                "clientAppId",
                "upstreamSystemId",
                "sourceSystem",
                "sourceTenantId",
                "taskId",
                "contextId",
                "providerTaskId");
    }

    private boolean hasForbiddenRuntimeStateAuditCredential(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        if (hasAnyHeader(request,
                "Authorization",
                "X-API-Key",
                "X-Navigator-API-Key",
                "X-Navi-Admin-Key",
                "X-Navi-Operator-Key",
                "X-Navi-Principal-Credential",
                "X-Client-App-Control-Key",
                "X-Client-App-Access-Token",
                "X-App-Access-Token",
                "X-Foggy-App-Access-Token",
                "X-Task-Token",
                "X-Worker-Token",
                "X-Tenant-Id",
                "X-Platform-Admin-Key",
                "X-System-Admin-Key",
                "X-Operator-Token",
                "X-Principal-Token")) {
            return true;
        }
        return hasAnyNormalizedParameter(request,
                "tenantId",
                "targetTenantId",
                "clientAppId",
                "upstreamSystemId",
                "sourceSystem",
                "sourceTenantId",
                "contextId",
                "providerTaskId");
    }

    private String runtimeAuditAppKey(HttpServletRequest request) {
        return firstHeader(request,
                "X-Client-App-Key",
                "X-App-Key",
                "X-Foggy-App-Key");
    }

    private String runtimeAuditAppSecret(HttpServletRequest request) {
        return firstHeader(request,
                "X-Client-App-Secret",
                "X-App-Secret",
                "X-Foggy-App-Secret");
    }

    private String runtimeUpstreamUserId(HttpServletRequest request) {
        return firstHeader(request,
                "X-Upstream-User-Id",
                "X-Foggy-Upstream-User-Id",
                "X-Client-Upstream-User-Id");
    }

    private boolean hasAnyHeader(HttpServletRequest request, String... names) {
        for (String name : names) {
            if (StringUtils.hasText(request.getHeader(name))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasAnyNormalizedParameter(HttpServletRequest request, String... names) {
        Set<String> forbidden = Arrays.stream(names)
                .map(this::normalizeParameterName)
                .collect(Collectors.toSet());
        for (String name : request.getParameterMap().keySet()) {
            if (forbidden.contains(normalizeParameterName(name))) {
                return true;
            }
        }
        return false;
    }

    private String normalizeParameterName(String name) {
        return name == null ? "" : name.replace("-", "").replace("_", "").toLowerCase(Locale.ROOT);
    }

    private Instant parseAuditInstant(String value, String errorCode) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value.trim()).toInstant();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(errorCode);
        }
    }

    private String runtimeCredentialErrorCode(Throwable error) {
        String message = error != null && error.getMessage() != null
                ? error.getMessage().toLowerCase(Locale.ROOT)
                : "";
        if (message.contains("expired")) {
            return "RUNTIME_CREDENTIAL_EXPIRED";
        }
        if (message.contains("not active")) {
            return "RUNTIME_CREDENTIAL_INACTIVE";
        }
        if (message.contains("required")) {
            return "RUNTIME_CREDENTIAL_REQUIRED";
        }
        return "RUNTIME_CREDENTIAL_INVALID";
    }

    private String safeSmokeErrorCode(Throwable error) {
        String stable = stableErrorCode(error != null ? error.getMessage() : null);
        return stable != null ? stable : "SAFE_SMOKE_REJECTED";
    }

    private String runtimeAuditErrorCode(Throwable error, String fallback) {
        String stable = stableErrorCode(error != null ? error.getMessage() : null);
        return stable != null ? stable : fallback;
    }

    private String stableErrorCode(String message) {
        if (!StringUtils.hasText(message)) {
            return null;
        }
        for (String part : message.trim().split("[^A-Za-z0-9_]+")) {
            if (SANITIZED_RUNTIME_ERROR_CODES.contains(part)) {
                return part;
            }
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return StringUtils.hasText(text) ? text : null;
    }

    @SafeVarargs
    private final <T> T firstNonNull(T... values) {
        if (values == null) {
            return null;
        }
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private Object firstPresent(Map<String, Object> map, String... keys) {
        if (map == null || map.isEmpty() || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (map.containsKey(key)) {
                return map.get(key);
            }
        }
        return null;
    }

    private Integer integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            if ("true".equalsIgnoreCase(text.trim()) || "false".equalsIgnoreCase(text.trim())) {
                return Boolean.parseBoolean(text.trim());
            }
        }
        return null;
    }

    private LocalDateTime latestTime(LocalDateTime... values) {
        LocalDateTime latest = null;
        if (values == null) {
            return null;
        }
        for (LocalDateTime value : values) {
            if (value != null && (latest == null || value.isAfter(latest))) {
                latest = value;
            }
        }
        return latest;
    }

    private LocalDateTime localDateTimeValue(Map<String, Object> map, String... keys) {
        Object value = firstPresent(map, keys);
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        if (!(value instanceof String text) || !StringUtils.hasText(text)) {
            return null;
        }
        String normalized = text.trim();
        try {
            return LocalDateTime.parse(normalized);
        } catch (Exception ignored) {
            try {
                return LocalDateTime.ofInstant(Instant.parse(normalized), ZoneOffset.UTC);
            } catch (Exception ignoredAgain) {
                return null;
            }
        }
    }

    private String resolveBaseUrl(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwardedProto = firstHeader(request, "X-Forwarded-Proto");
        String forwardedHost = firstHeader(request, "X-Forwarded-Host");
        if (StringUtils.hasText(forwardedProto) && StringUtils.hasText(forwardedHost)) {
            return forwardedProto + "://" + forwardedHost;
        }
        StringBuilder base = new StringBuilder();
        base.append(request.getScheme()).append("://").append(request.getServerName());
        int port = request.getServerPort();
        if (port > 0 && port != 80 && port != 443) {
            base.append(":").append(port);
        }
        return base.toString();
    }

    /**
     * 轮询 Agent 任务状态
     * <p>
     * COMPLETED 时包含执行结果、耗时和费用信息。
     */
    @GetMapping("/agents/{agentId}/tasks/{taskId}")
    public RX<OpenApiTaskDTO> getTaskStatus(
            @PathVariable String agentId,
            @PathVariable String taskId,
            HttpServletRequest request) {
        ResolvedClientAppCredentialDTO clientAppCredential = requireClientAppRuntimeToken(request);
        OpenApiAgentRouteService.ResolvedOpenApiAgentRoute route =
                requireOpenApiAgentRoute(agentId, clientAppCredential);
        String tenantId = clientAppCredential.getTenantId();
        AgentResolveContext ctx = AgentResolveContext.builder()
                .tenantId(tenantId).requestSource("OPEN_API").build();
        A2aAgent agent = agentResolver.resolveAgent(route.agentId(), ctx)
                .orElseThrow(() -> RX.throwB("Agent not found: " + route.agentId()));

        SessionTaskEntity taskEntity = sessionQueryService.findTask(taskId)
                .filter(entity -> tenantId.equals(entity.getTenantId()) && route.agentId().equals(entity.getAgentId()))
                .orElse(null);
        A2aTask task = agent.getTask(taskId).orElse(null);
        if (task == null && taskEntity == null) {
            throw RX.throwB("Task not found: " + taskId);
        }
        if (task == null) {
            return RX.ok(toOpenApiTaskDTO(taskEntity, route.agentId(), resolveContextIdFromSession(taskEntity.getSessionId())));
        }
        return RX.ok(toOpenApiTaskDTO(task, route.agentId(), taskEntity));
    }

    /**
     * 获取任务诊断事实快照。
     * <p>
     * 该接口只暴露可观测事实，不进行恢复状态裁决。
     */
    @GetMapping("/agents/{agentId}/tasks/{taskId}/diagnostics")
    public RX<OpenTaskDiagnosticsDTO> getTaskDiagnostics(
            @PathVariable String agentId,
            @PathVariable String taskId,
            HttpServletRequest request) {
        ResolvedClientAppCredentialDTO clientAppCredential = requireClientAppRuntimeToken(request);
        OpenApiAgentRouteService.ResolvedOpenApiAgentRoute route =
                requireOpenApiAgentRoute(agentId, clientAppCredential);
        String tenantId = clientAppCredential.getTenantId();
        resolveOpenApiAgent(route.agentId(), tenantId);

        return RX.ok(durableTaskSessionQueryFacade.loadTaskDiagnostics(
                taskId, tenantId, route.agentId()));
    }

    /**
     * 获取任务完成证据引用。
     * <p>
     * 只返回摘要和引用，不返回原始执行报告或完整 artifact 内容。
     */
    @GetMapping("/agents/{agentId}/tasks/{taskId}/evidence")
    public RX<OpenTaskEvidenceDTO> getTaskEvidence(
            @PathVariable String agentId,
            @PathVariable String taskId,
            HttpServletRequest request) {
        ResolvedClientAppCredentialDTO clientAppCredential = requireClientAppRuntimeToken(request);
        OpenApiAgentRouteService.ResolvedOpenApiAgentRoute route =
                requireOpenApiAgentRoute(agentId, clientAppCredential);
        String tenantId = clientAppCredential.getTenantId();
        resolveOpenApiAgent(route.agentId(), tenantId);

        return RX.ok(durableTaskSessionQueryFacade.loadTaskEvidence(
                taskId, tenantId, route.agentId()));
    }

    /**
     * 取消 Agent 任务
     */
    @PostMapping("/agents/{agentId}/tasks/{taskId}/cancel")
    @RequireAuth(roles = {"TENANT_ADMIN", "DEVELOPER"})
    public RX<OpenApiTaskDTO> cancelTask(
            @PathVariable String agentId,
            @PathVariable String taskId) {
        String tenantId = UserContext.getCurrentTenantId();
        AgentResolveContext ctx = AgentResolveContext.builder()
                .tenantId(tenantId).requestSource("OPEN_API").build();
        A2aAgent agent = agentResolver.resolveAgent(agentId, ctx)
                .orElseThrow(() -> RX.throwB("Agent not found: " + agentId));
        agent.cancelTask(taskId);

        // 重新查询任务状态返回
        A2aTask task = agent.getTask(taskId)
                .orElse(null);
        if (task != null) {
            return RX.ok(toOpenApiTaskDTO(task, agentId));
        }
        return RX.ok(OpenApiTaskDTO.builder()
                .taskId(taskId).status("CANCELLED").build());
    }

    /**
     * 列出 Agent 的活跃任务（RUNNING + AWAITING_PERMISSION）
     * <p>
     * 第三方可定期调用此端点监控当前正在执行的任务。
     */
    @GetMapping("/agents/{agentId}/tasks")
    @RequireAuth(roles = {"TENANT_ADMIN", "DEVELOPER"})
    public RX<List<OpenApiTaskDTO>> listAgentTasks(@PathVariable String agentId) {
        String tenantId = UserContext.getCurrentTenantId();

        // 获取 Agent 实体以读取 userId
        CodingAgentEntity agentEntity = codingAgentRepository.findByAgentIdAndTenantId(agentId, tenantId)
                .orElseThrow(() -> RX.throwB("Agent not found: " + agentId));

        List<OpenApiTaskDTO> result = taskDispatchFacade.listActiveTasks(agentEntity.getUserId()).stream()
                .filter(dto -> agentId.equals(dto.getAgentId()))
                .map(dto -> taskProjectionMapper.mapActiveTask(dto, agentId))
                .toList();
        return RX.ok(result);
    }

    // ===== 6b. Agent 任务增量消息（上游接入首版） =====

    /**
     * 轮询任务执行中的新增消息
     * <p>
     * 首次不传 cursor，后续传 nextCursor 拉取增量。
     * 只返回该 taskId 对应的消息，按时间升序。
     */
    @GetMapping("/agents/{agentId}/tasks/{taskId}/messages")
    public RX<OpenTaskMessagesResponse> getTaskMessages(
            @PathVariable String agentId,
            @PathVariable String taskId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "false") boolean includeInternal,
            HttpServletRequest request) {
        ResolvedClientAppCredentialDTO clientAppCredential = requireClientAppRuntimeToken(request);
        OpenApiAgentRouteService.ResolvedOpenApiAgentRoute route =
                requireOpenApiAgentRoute(agentId, clientAppCredential);
        String tenantId = clientAppCredential.getTenantId();
        resolveOpenApiAgent(route.agentId(), tenantId);

        return RX.ok(durableTaskSessionQueryFacade.loadTaskMessages(
                taskId, tenantId, route.agentId(), cursor, limit, includeInternal));
    }

    // ===== 6c. Agent 会话列表与消息（上游接入首版） =====

    /**
     * 获取会话上下文列表
     */
    @GetMapping("/agents/{agentId}/sessions")
    public RX<OpenSessionListResponse> listAgentSessions(
            @PathVariable String agentId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String cursor,
            HttpServletRequest request) {
        ResolvedClientAppCredentialDTO clientAppCredential = requireClientAppRuntimeToken(request);
        OpenApiAgentRouteService.ResolvedOpenApiAgentRoute route =
                requireOpenApiAgentRoute(agentId, clientAppCredential);
        String tenantId = clientAppCredential.getTenantId();
        String userId = resolveAgentOwnerUserId(route.agentId(), tenantId);
        resolveOpenApiAgent(route.agentId(), tenantId);

        return RX.ok(durableTaskSessionQueryFacade.listSessions(
                userId, route.agentId(), limit, cursor));
    }

    /**
     * 获取指定会话上下文下的消息列表
     */
    @GetMapping("/agents/{agentId}/sessions/{contextId}/messages")
    public RX<OpenSessionMessagesResponse> getSessionMessages(
            @PathVariable String agentId,
            @PathVariable String contextId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "false") boolean includeInternal,
            HttpServletRequest request) {
        ResolvedClientAppCredentialDTO clientAppCredential = requireClientAppRuntimeToken(request);
        OpenApiAgentRouteService.ResolvedOpenApiAgentRoute route =
                requireOpenApiAgentRoute(agentId, clientAppCredential);
        String tenantId = clientAppCredential.getTenantId();
        String userId = resolveAgentOwnerUserId(route.agentId(), tenantId);
        resolveOpenApiAgent(route.agentId(), tenantId);

        return RX.ok(durableTaskSessionQueryFacade.loadSessionMessages(
                contextId, userId, cursor, limit, includeInternal));
    }

    // ===== 7. Worker 进程管理 =====

    /**
     * 列出 Worker 上的 CLI 进程（含孤儿检测标记）
     * <p>
     * 返回 Worker 上所有 Claude CLI 进程，并标注 Reconciler 识别的孤儿进程。
     * 孤儿进程指 DB 任务已结束但 CLI 仍在运行的进程。
     */
    @SuppressWarnings("unchecked")
    @GetMapping("/workers/{workerId}/processes")
    @RequireAuth(roles = {"TENANT_ADMIN"})
    public RX<Map<String, Object>> listWorkerProcesses(@PathVariable String workerId) {
        ClaudeWorkerEntity worker = resolveWorkerByTenant(workerId);
        ClaudeWorkerClient client = workerService.createClient(worker);
        try {
            Map<String, Object> result = client.listCliProcesses()
                    .block(Duration.ofSeconds(10));
            if (result != null) {
                enrichWithOrphanInfo(workerId, result);
            }
            return RX.ok(result);
        } catch (Exception e) {
            log.warn("Failed to list CLI processes for worker {}: type={}",
                    workerId, e.getClass().getSimpleName());
            return RX.failA("获取 CLI 进程列表失败: CLAUDE_WORKER_PROCESS_QUERY_FAILED");
        }
    }

    /**
     * 杀死 Worker 上的 CLI 进程
     * <p>
     * 支持 force=true（SIGKILL）和 force=false（SIGTERM）。
     * 主要用于清理孤儿进程。
     */
    @PostMapping("/workers/{workerId}/processes/{pid}/kill")
    @RequireAuth(roles = {"TENANT_ADMIN"})
    public RX<Map<String, Object>> killWorkerProcess(
            @PathVariable String workerId,
            @PathVariable int pid,
            @RequestBody(required = false) Map<String, Object> body) {
        ClaudeWorkerEntity worker = resolveWorkerByTenant(workerId);
        boolean force = false;
        if (body != null && body.containsKey("force")) {
            force = Boolean.TRUE.equals(body.get("force"));
        }
        ClaudeWorkerClient client = workerService.createClient(worker);
        ClaudeTaskService.ManualPidKillRequest operation = null;
        try {
            Map<String, Object> snapshot = client.listCliProcesses().block(Duration.ofSeconds(5));
            String taskId = resolveBoundProcessTaskId(snapshot, pid, body);
            String processIdentity = resolveProcessIdentity(snapshot, pid, taskId);
            if (taskId == null || processIdentity == null) {
                return RX.failA("仅允许终止已绑定到活动任务的 CLI PID");
            }
            operation = claudeTaskService.prepareManualPidKill(taskId, workerId,
                    UserContext.getCurrentUserId(), "TENANT_ADMIN_MANUAL",
                    UserContext.getCurrentTenantId(), true, pid, processIdentity,
                    client.terminationSigningSecret());
            Map<String, Object> workerResult = client.killCliProcess(pid, force, operation.capability())
                    .block(Duration.ofSeconds(10));
            Map<String, Object> result = workerResult == null
                    ? new LinkedHashMap<>() : new LinkedHashMap<>(workerResult);
            claudeTaskService.recordManualPidKillResult(operation, result);
            result.put("termination_operation_id", operation.operationId());
            log.info("Open API dispatched explicit CLI PID operation: workerId={}, pid={}, operationId={}",
                    workerId, pid, operation.operationId());
            return RX.ok(result);
        } catch (Exception e) {
            if (operation != null) {
                claudeTaskService.markManualPidKillDispatchFailure(operation, e);
            }
            log.warn("Failed to kill CLI process {} for worker {}: type={}",
                    pid, workerId, e.getClass().getSimpleName());
            return RX.failA("终止 CLI 进程失败: CLAUDE_WORKER_TERMINATION_UNCONFIRMED");
        }
    }

    // ===== 内部工具方法 =====

    @SuppressWarnings("unchecked")
    private String resolveBoundProcessTaskId(Map<String, Object> snapshot, int pid,
                                             Map<String, Object> body) {
        if (snapshot == null || !(snapshot.get("processes") instanceof List<?> processes)) return null;
        for (Object item : processes) {
            if (!(item instanceof Map<?, ?> raw)) continue;
            Object candidatePid = raw.get("pid");
            if (!pidMatches(candidatePid, pid)) continue;
            Object task = raw.get("foggy_task_id");
            if (task == null || String.valueOf(task).isBlank()) return null;
            String boundTaskId = String.valueOf(task);
            Object requested = body == null ? null : body.get("taskId");
            if (requested != null && !String.valueOf(requested).isBlank()
                    && !boundTaskId.equals(String.valueOf(requested))) {
                throw new IllegalArgumentException("TERMINATION_WORKER_TASK_MISMATCH");
            }
            return boundTaskId;
        }
        return null;
    }

    private String resolveProcessIdentity(Map<String, Object> snapshot, int pid, String taskId) {
        if (snapshot == null || !StringUtils.hasText(taskId)
                || !(snapshot.get("processes") instanceof List<?> processes)) return null;
        for (Object item : processes) {
            if (!(item instanceof Map<?, ?> raw)) continue;
            if (!pidMatches(raw.get("pid"), pid)) continue;
            String boundTaskId = stringValue(raw.get("foggy_task_id"));
            if (!taskId.equals(boundTaskId)) continue;
            String identity = stringValue(raw.get("process_identity"));
            return isSafeProcessIdentity(identity) ? identity : null;
        }
        return null;
    }

    private boolean pidMatches(Object value, int pid) {
        if (value instanceof Number number) return number.intValue() == pid;
        try {
            return value != null && Integer.parseInt(String.valueOf(value)) == pid;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private boolean isSafeProcessIdentity(String value) {
        return value != null && value.length() <= 160
                && value.matches("[A-Za-z0-9][A-Za-z0-9._:+-]{0,159}");
    }

    /**
     * 验证 Agent 存在并返回（Open API 上下文）
     */
    private A2aAgent resolveOpenApiAgent(String agentId, String tenantId) {
        return agentResolver.resolveAgent(agentId, AgentResolveContext.builder()
                        .tenantId(tenantId).requestSource("OPEN_API").build())
                .orElseThrow(() -> RX.throwB("Agent not found: " + agentId));
    }

    private String resolveAgentOwnerUserId(String agentId, String tenantId) {
        return codingAgentRepository.findByAgentIdAndTenantId(agentId, tenantId)
                .map(CodingAgentEntity::getUserId)
                .orElseThrow(() -> RX.throwB("Agent not found: " + agentId));
    }

    /**
     * 租户级 Worker 校验：Worker 必须属于当前租户
     */
    private ClaudeWorkerEntity resolveWorkerByTenant(String workerId) {
        String tenantId = UserContext.getCurrentTenantId();
        ClaudeWorkerEntity entity = workerRepository.findByWorkerId(workerId)
                .orElseThrow(() -> RX.throwB("Worker not found: " + workerId));
        if (!tenantId.equals(entity.getTenantId())) {
            throw RX.throwB("Worker not found: " + workerId);
        }
        return entity;
    }

    /**
     * 注入 Reconciler 的孤儿信息到进程列表
     */
    @SuppressWarnings("unchecked")
    private void enrichWithOrphanInfo(String workerId, Map<String, Object> result) {
        Object procObj = result.get("processes");
        if (!(procObj instanceof List<?> rawList)) return;
        for (Object item : rawList) {
            if (!(item instanceof Map<?, ?> rawProc)) continue;
            Map<String, Object> proc = (Map<String, Object>) rawProc;
            Object pidObj = proc.get("pid");
            if (pidObj == null) continue;
            int pid = ((Number) pidObj).intValue();
            Instant firstSeen = reconciler.getOrphanFirstSeen(workerId, pid);
            if (firstSeen != null) {
                proc.put("orphan_first_seen_at", firstSeen.toString());
                proc.put("is_orphan", true);
            }
        }
    }

    /**
     * 内部任务状态 → Open API 状态映射
     * <p>
     * 对外状态枚举：SUBMITTED | RUNNING | AWAITING_INPUT | COMPLETED | FAILED | CANCELLED
     */
    private String mapTaskStatus(String internalStatus) {
        return taskProjectionMapper.mapTaskStatus(internalStatus);
    }

    /**
     * A2aTaskState → Open API 外部状态
     */
    private String mapA2aState(A2aTaskState state) {
        return taskProjectionMapper.mapA2aState(state);
    }

    /**
     * A2aTask → OpenApiTaskDTO 转换（简化面向第三方的响应）
     */
    private OpenApiTaskDTO toOpenApiTaskDTO(A2aTask task, String agentId) {
        return taskProjectionMapper.mapA2aTask(objectMapper, task, agentId, null);
    }

    private OpenApiTaskDTO toOpenApiTaskDTO(A2aTask task, String agentId, SessionTaskEntity taskEntity) {
        return taskProjectionMapper.mapA2aTask(objectMapper, task, agentId, taskEntity);
    }

    private OpenApiTaskDTO toOpenApiTaskDTO(SessionTaskEntity taskEntity, String agentId, String contextId) {
        return taskProjectionMapper.mapDurableTask(objectMapper, taskEntity, agentId, contextId);
    }

    private String inferFailureStageFromText(
            String status,
            String providerType,
            String workerBackend,
            String failureSummary) {
        return taskProjectionMapper.inferFailureStageFromText(
                status, providerType, workerBackend, failureSummary);
    }

    private String sanitizeDiagnosticText(String text) {
        return taskProjectionMapper.sanitizeDiagnosticText(text);
    }

    private String workerBackendFromProviderType(String providerType) {
        return taskProjectionMapper.workerBackendFromProviderType(providerType);
    }

    private String terminalStatusFromTaskStatus(String status) {
        return taskProjectionMapper.terminalStatusFromTaskStatus(status);
    }

    private String serializeClientContext(Map<String, Object> clientContext) {
        if (clientContext == null || clientContext.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(clientContext);
        } catch (Exception e) {
            throw RX.throwB("clientContext must be a valid JSON object");
        }
    }

    /**
     * 从 sessionId 反查 contextId（通过 navigatorSessionId 映射）
     */
    private String resolveContextIdFromSession(String sessionId) {
        if (sessionId == null) return null;
        return sessionQueryService.resolveContextId(sessionId).orElse(null);
    }
}
