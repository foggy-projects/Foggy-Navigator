package com.foggy.navigator.claude.worker.controller.openapi;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.claude.worker.client.ClaudeWorkerClient;
import com.foggy.navigator.claude.worker.model.dto.*;
import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import com.foggy.navigator.claude.worker.model.form.*;
import com.foggy.navigator.business.agent.model.dto.ClientAppRuntimeAccessTokenDTO;
import com.foggy.navigator.business.agent.model.dto.ResolvedClientAppCredentialDTO;
import com.foggy.navigator.business.agent.service.BusinessAgentTaskService;
import com.foggy.navigator.business.agent.service.ClientAppRuntimeCredentialResolver;
import com.foggy.navigator.claude.worker.repository.CodingAgentRepository;
import com.foggy.navigator.claude.worker.repository.ClaudeWorkerRepository;
import com.foggy.navigator.common.repository.WorkingDirectoryRepository;
import com.foggy.navigator.claude.worker.service.*;
import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.a2a.*;
import com.foggy.navigator.common.entity.AgentConversationContextEntity;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.common.entity.SessionMessageEntity;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.util.IdGenerator;
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
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    private final OpenApiProvisioningService provisioningService;
    private final ClaudeWorkerService workerService;
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
    private final ObjectProvider<ClientAppRuntimeCredentialResolver> clientAppCredentialResolver;
    private final ObjectProvider<BusinessAgentTaskService> businessAgentTaskService;

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
        String userId = UserContext.getCurrentUserId();
        String tenantId = UserContext.getCurrentTenantId();

        // 验证 Worker 属于同租户
        ClaudeWorkerEntity worker = workerRepository.findByWorkerId(form.getWorkerId())
                .orElseThrow(() -> RX.throwB("Worker not found: " + form.getWorkerId()));
        if (!tenantId.equals(worker.getTenantId())) {
            throw RX.throwB("Worker not found: " + form.getWorkerId());
        }

        if (form.getFiles() == null || form.getFiles().isEmpty()) {
            return RX.failB("files is required");
        }

        try {
            String directoryId = claudeWorkerFacade.initDirectory(
                    worker.getUserId(), form.getWorkerId(), form.getPath(),
                    form.getFiles(), form.getProjectName());

            // 获取完整 DTO 返回
            WorkingDirectoryDTO dto = directoryService.getDirectory(worker.getUserId(), directoryId);
            return RX.ok(dto);
        } catch (Exception e) {
            log.error("Directory init failed: {}", e.getMessage(), e);
            return RX.failA("Directory initialization failed: " + e.getMessage());
        }
    }

    @GetMapping("/directories")
    @RequireAuth(roles = {"TENANT_ADMIN"})
    public RX<List<WorkingDirectoryDTO>> listDirectories(
            @RequestParam(required = false) String workerId) {
        String tenantId = UserContext.getCurrentTenantId();

        List<WorkingDirectoryDTO> dirs;
        if (workerId != null && !workerId.isBlank()) {
            // 验证 Worker 属于同租户
            ClaudeWorkerEntity worker = workerRepository.findByWorkerId(workerId)
                    .orElseThrow(() -> RX.throwB("Worker not found: " + workerId));
            if (!tenantId.equals(worker.getTenantId())) {
                throw RX.throwB("Worker not found: " + workerId);
            }
            dirs = directoryRepository.findByWorkerIdOrderByProjectNameAsc(workerId).stream()
                    .map(e -> WorkingDirectoryDTO.builder()
                            .directoryId(e.getDirectoryId())
                            .workerId(e.getWorkerId())
                            .projectName(e.getProjectName())
                            .path(e.getPath())
                            .directoryType(e.getDirectoryType())
                            .gitBranch(e.getGitBranch())
                            .createdAt(e.getCreatedAt())
                            .updatedAt(e.getUpdatedAt())
                            .build())
                    .toList();
        } else {
            dirs = directoryRepository.findByTenantId(tenantId).stream()
                    .map(e -> WorkingDirectoryDTO.builder()
                            .directoryId(e.getDirectoryId())
                            .workerId(e.getWorkerId())
                            .projectName(e.getProjectName())
                            .path(e.getPath())
                            .directoryType(e.getDirectoryType())
                            .gitBranch(e.getGitBranch())
                            .createdAt(e.getCreatedAt())
                            .updatedAt(e.getUpdatedAt())
                            .build())
                    .toList();
        }
        return RX.ok(dirs);
    }

    @GetMapping("/directories/{directoryId}")
    @RequireAuth(roles = {"TENANT_ADMIN"})
    public RX<WorkingDirectoryDTO> getDirectory(@PathVariable String directoryId) {
        String tenantId = UserContext.getCurrentTenantId();
        var entity = directoryRepository.findByDirectoryId(directoryId)
                .orElseThrow(() -> RX.throwB("Directory not found: " + directoryId));
        if (!tenantId.equals(entity.getTenantId())) {
            throw RX.throwB("Directory not found: " + directoryId);
        }
        return RX.ok(directoryService.getDirectory(entity.getUserId(), directoryId));
    }

    @DeleteMapping("/directories/{directoryId}")
    @RequireAuth(roles = {"TENANT_ADMIN"})
    public RX<Void> deleteDirectory(@PathVariable String directoryId) {
        String tenantId = UserContext.getCurrentTenantId();
        var entity = directoryRepository.findByDirectoryId(directoryId)
                .orElseThrow(() -> RX.throwB("Directory not found: " + directoryId));
        if (!tenantId.equals(entity.getTenantId())) {
            throw RX.throwB("Directory not found: " + directoryId);
        }
        directoryService.deleteDirectory(entity.getUserId(), directoryId);
        return RX.ok(null);
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
        String tenantId = UserContext.getCurrentTenantId();
        var entity = directoryRepository.findByDirectoryId(directoryId)
                .orElseThrow(() -> RX.throwB("Directory not found: " + directoryId));
        if (!tenantId.equals(entity.getTenantId())) {
            throw RX.throwB("Directory not found: " + directoryId);
        }
        try {
            String json = (envVars == null || envVars.isEmpty()) ? null
                    : new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(envVars);
            entity.setCustomEnvVars(json);
            directoryRepository.save(entity);
            log.info("Updated customEnvVars for directory {}: {} keys", directoryId,
                    envVars != null ? envVars.size() : 0);
            return RX.ok(envVars);
        } catch (Exception e) {
            return RX.failA("Failed to update env vars: " + e.getMessage());
        }
    }

    /**
     * 更新目录中的文件（覆盖写入）
     * <p>
     * 支持更新 CLAUDE.md、.claude/skills/ 等文件。
     * 文件路径为相对于工作目录的相对路径。
     */
    @PutMapping("/directories/{directoryId}/files")
    @RequireAuth(roles = {"TENANT_ADMIN"})
    public RX<Map<String, Object>> updateDirectoryFiles(
            @PathVariable String directoryId,
            @RequestBody Map<String, String> files) {
        String tenantId = UserContext.getCurrentTenantId();
        var entity = directoryRepository.findByDirectoryId(directoryId)
                .orElseThrow(() -> RX.throwB("Directory not found: " + directoryId));
        if (!tenantId.equals(entity.getTenantId())) {
            throw RX.throwB("Directory not found: " + directoryId);
        }
        if (files == null || files.isEmpty()) {
            return RX.failB("files is required");
        }
        try {
            ClaudeWorkerEntity worker = workerRepository.findByWorkerId(entity.getWorkerId())
                    .orElseThrow(() -> RX.throwB("Worker not found: " + entity.getWorkerId()));
            ClaudeWorkerClient client = workerService.createClient(worker);
            Map<String, Object> result = client.initDirectory(entity.getPath(), files)
                    .block(Duration.ofSeconds(30));
            log.info("Updated {} files in directory {}", files.size(), directoryId);
            return RX.ok(result);
        } catch (Exception e) {
            log.error("Failed to update files in directory {}: {}", directoryId, e.getMessage(), e);
            return RX.failA("Failed to update files: " + e.getMessage());
        }
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
    @RequireAuth(roles = {"TENANT_ADMIN", "DEVELOPER"})
    public RX<ClientAppRuntimeAccessTokenDTO> issueClientAppRuntimeToken(HttpServletRequest request) {
        String tenantId = UserContext.getCurrentTenantId();
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
        return RX.ok(resolver.issueAccessToken(tenantId, appKey, appSecret));
    }

    /**
     * 向 Agent 发送查询（异步模式）
     * <p>
     * 立即返回 SUBMITTED 状态的任务，调用者通过轮询端点获取结果。
     * 支持多轮会话：首次不传 contextId 则平台自动生成；
     * 后续传入相同 contextId 可恢复 Claude 会话上下文。
     */
    @PostMapping("/agents/{agentId}/ask")
    @RequireAuth(roles = {"TENANT_ADMIN", "DEVELOPER"})
    public RX<OpenApiTaskDTO> askAgent(
            @PathVariable String agentId,
            @RequestBody OpenApiQueryForm form,
            HttpServletRequest request) {
        String tenantId = UserContext.getCurrentTenantId();
        ResolvedClientAppCredentialDTO clientAppCredential = resolveClientAppCredential(
                tenantId, agentId, request);

        String messageContent = form.resolveMessage();
        if (messageContent == null || messageContent.isBlank()) {
            return RX.failB("message is required");
        }

        AgentResolveContext ctx = AgentResolveContext.builder()
                .tenantId(tenantId)
                .modelConfigId(form.getMetadata() != null ? (String) form.getMetadata().get("modelConfigId") : null)
                .requestSource("OPEN_API")
                .build();
        A2aAgent agent = agentResolver.resolveAgent(agentId, ctx)
                .orElseThrow(() -> RX.throwB("Agent not found: " + agentId));

        // 构建 A2aMessage
        String contextId = (form.getContextId() != null && !form.getContextId().isBlank())
                ? form.getContextId() : IdGenerator.shortId();
        A2aMessage message = A2aMessage.user(List.of(A2aPart.text(messageContent)));
        message.setContextId(contextId);
        Map<String, Object> metadata = new java.util.HashMap<>();
        // 合并用户传入的扩展元数据
        if (form.getMetadata() != null && !form.getMetadata().isEmpty()) {
            metadata.putAll(form.getMetadata());
        }
        metadata.remove("runtimeContext");
        metadata.remove("runtime_context");
        if (form.getMaxTurns() != null) {
            metadata.put("maxTurns", form.getMaxTurns());
        }
        if (form.getSystemPrompt() != null && !form.getSystemPrompt().isBlank()) {
            metadata.put("systemPrompt", form.getSystemPrompt());
        }
        if (form.getFirstMsg() != null && !form.getFirstMsg().isBlank()) {
            metadata.put("firstMsg", form.getFirstMsg());
        }
        enrichBusinessRuntimeContext(tenantId, metadata, agentId, clientAppCredential, request, contextId);
        if (!metadata.isEmpty()) {
            message.setMetadata(metadata);
        }

        A2aTask task = agent.sendTask(message);

        log.info("Open API askAgent: agentId={}, taskId={}, tenantId={}", agentId, task.getId(), tenantId);

        return RX.ok(toOpenApiTaskDTO(task, agentId));
    }

    private ResolvedClientAppCredentialDTO resolveClientAppCredential(
            String tenantId,
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

        return resolver.resolveAccessTokenForSkill(tenantId, appKey, accessToken, skillId)
                .orElse(null);
    }

    private void enrichBusinessRuntimeContext(
            String tenantId,
            Map<String, Object> metadata,
            String skillId,
            ResolvedClientAppCredentialDTO clientAppCredential,
            HttpServletRequest request,
            String contextId) {
        if (clientAppCredential == null) {
            return;
        }

        Map<String, Object> context = new LinkedHashMap<>();
        Object rawContext = metadata.get("context");
        if (rawContext instanceof Map<?, ?> existingContext) {
            existingContext.forEach((key, value) -> {
                if (key instanceof String stringKey) {
                    context.put(stringKey, value);
                }
            });
        }

        context.putIfAbsent("clientAppId", clientAppCredential.getClientAppId());
        context.putIfAbsent("businessSkillId", skillId);
        context.putIfAbsent("credentialId", clientAppCredential.getCredentialId());
        context.putIfAbsent("auto_inject_app_public_skills", true);

        String upstreamUserId = firstHeader(request,
                "X-Upstream-User-Id",
                "X-Foggy-Upstream-User-Id",
                "X-Client-Upstream-User-Id");
        if (StringUtils.hasText(upstreamUserId)) {
            context.putIfAbsent("upstreamUserId", upstreamUserId);
            issueBusinessRuntimeToken(
                    tenantId,
                    UserContext.getCurrentUserId(),
                    metadata,
                    clientAppCredential.getClientAppId(),
                    upstreamUserId,
                    skillId,
                    contextId,
                    metadata.get("modelConfigId"));
        }

        metadata.put("context", context);
    }

    private void issueBusinessRuntimeToken(
            String tenantId,
            String actorUserId,
            Map<String, Object> metadata,
            String clientAppId,
            String upstreamUserId,
            String skillId,
            String sessionId,
            Object requestedModelConfigId) {
        BusinessAgentTaskService service = businessAgentTaskService.getIfAvailable();
        if (service == null) {
            return;
        }
        String token = service.issueOpenApiTaskScopedToken(
                tenantId,
                actorUserId,
                clientAppId,
                upstreamUserId,
                skillId,
                sessionId,
                requestedModelConfigId instanceof String value ? value : null);

        Map<String, Object> runtimeContext = new LinkedHashMap<>();
        Object existingRuntimeContext = metadata.get("runtimeContext");
        if (existingRuntimeContext instanceof Map<?, ?> existingMap) {
            existingMap.forEach((key, value) -> {
                if (key instanceof String stringKey) {
                    runtimeContext.put(stringKey, value);
                }
            });
        }
        runtimeContext.put("task_scoped_token", token);
        metadata.put("runtimeContext", runtimeContext);
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

    /**
     * 轮询 Agent 任务状态
     * <p>
     * COMPLETED 时包含执行结果、耗时和费用信息。
     */
    @GetMapping("/agents/{agentId}/tasks/{taskId}")
    @RequireAuth(roles = {"TENANT_ADMIN", "DEVELOPER"})
    public RX<OpenApiTaskDTO> getTaskStatus(
            @PathVariable String agentId,
            @PathVariable String taskId) {
        String tenantId = UserContext.getCurrentTenantId();
        AgentResolveContext ctx = AgentResolveContext.builder()
                .tenantId(tenantId).requestSource("OPEN_API").build();
        A2aAgent agent = agentResolver.resolveAgent(agentId, ctx)
                .orElseThrow(() -> RX.throwB("Agent not found: " + agentId));

        A2aTask task = agent.getTask(taskId)
                .orElseThrow(() -> RX.throwB("Task not found: " + taskId));
        return RX.ok(toOpenApiTaskDTO(task, agentId));
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
        CodingAgentEntity agentEntity = codingAgentRepository.findByAgentId(agentId)
                .filter(entity -> tenantId.equals(entity.getTenantId()))
                .orElseThrow(() -> RX.throwB("Agent not found: " + agentId));

        List<OpenApiTaskDTO> result = taskDispatchFacade.listActiveTasks(agentEntity.getUserId()).stream()
                .filter(dto -> agentId.equals(dto.getAgentId()))
                .map(dto -> OpenApiTaskDTO.builder()
                        .taskId(dto.getTaskId())
                        .agentId(agentId)
                        .status(mapTaskStatus(dto.getStatus()))
                        .contextId(dto.getContextId())
                        .createdAt(dto.getCreatedAt())
                        .build())
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
    @RequireAuth(roles = {"TENANT_ADMIN", "DEVELOPER"})
    public RX<OpenTaskMessagesResponse> getTaskMessages(
            @PathVariable String agentId,
            @PathVariable String taskId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        String tenantId = UserContext.getCurrentTenantId();
        resolveOpenApiAgent(agentId, tenantId);

        // 验证 task 存在并获取 contextId
        SessionTaskEntity taskEntity = sessionQueryService.findTask(taskId)
                .orElseThrow(() -> RX.throwB("Task not found: " + taskId));

        // 获取 contextId（从 task 的 sessionId 反查 context）
        String contextId = resolveContextIdFromSession(taskEntity.getSessionId());

        int safeLimit = Math.min(Math.max(limit, 1), 200);
        List<SessionMessageEntity> messages = sessionQueryService.getTaskMessages(taskId, cursor, safeLimit);

        boolean hasMore = messages.size() > safeLimit;
        List<SessionMessageEntity> page = hasMore ? messages.subList(0, safeLimit) : messages;

        String nextCursor = page.isEmpty() ? cursor : page.get(page.size() - 1).getId();

        List<OpenSessionMessageDTO> dtos = page.stream()
                .map(m -> toOpenSessionMessageDTO(m, contextId))
                .toList();

        return RX.ok(OpenTaskMessagesResponse.builder()
                .taskId(taskId)
                .contextId(contextId)
                .messages(dtos)
                .nextCursor(nextCursor)
                .hasMore(hasMore)
                .build());
    }

    // ===== 6c. Agent 会话列表与消息（上游接入首版） =====

    /**
     * 获取会话上下文列表
     */
    @GetMapping("/agents/{agentId}/sessions")
    @RequireAuth(roles = {"TENANT_ADMIN", "DEVELOPER"})
    public RX<OpenSessionListResponse> listAgentSessions(
            @PathVariable String agentId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String cursor) {
        String userId = UserContext.getCurrentUserId();
        String tenantId = UserContext.getCurrentTenantId();
        resolveOpenApiAgent(agentId, tenantId);

        int safeLimit = Math.min(Math.max(limit, 1), 100);
        List<AgentConversationContextEntity> contexts = sessionQueryService.listSessions(
                userId, agentId, safeLimit);

        boolean hasMore = contexts.size() > safeLimit;
        List<AgentConversationContextEntity> page = hasMore ? contexts.subList(0, safeLimit) : contexts;

        // 批量预取 latestTaskId，消除 N+1
        List<String> sessionIds = page.stream()
                .map(AgentConversationContextEntity::getNavigatorSessionId)
                .filter(s -> s != null && !s.isBlank())
                .toList();
        Map<String, String> latestTaskMap = sessionQueryService.batchFindLatestTaskIds(sessionIds);

        List<OpenSessionSummaryDTO> dtos = page.stream()
                .map(ctx -> toSessionSummary(ctx, agentId, latestTaskMap))
                .toList();

        String nextCursor = page.isEmpty() ? null : page.get(page.size() - 1).getContextId();

        return RX.ok(OpenSessionListResponse.builder()
                .sessions(dtos)
                .nextCursor(nextCursor)
                .hasMore(hasMore)
                .build());
    }

    /**
     * 获取指定会话上下文下的消息列表
     */
    @GetMapping("/agents/{agentId}/sessions/{contextId}/messages")
    @RequireAuth(roles = {"TENANT_ADMIN", "DEVELOPER"})
    public RX<OpenSessionMessagesResponse> getSessionMessages(
            @PathVariable String agentId,
            @PathVariable String contextId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        String userId = UserContext.getCurrentUserId();
        String tenantId = UserContext.getCurrentTenantId();
        resolveOpenApiAgent(agentId, tenantId);

        // 解析 contextId → sessionId
        String sessionId = sessionQueryService.resolveSessionId(contextId, userId)
                .orElseThrow(() -> RX.throwB("Context not found: " + contextId));

        int safeLimit = Math.min(Math.max(limit, 1), 200);
        List<SessionMessageEntity> messages = sessionQueryService.getSessionMessages(
                sessionId, cursor, safeLimit);

        boolean hasMore = messages.size() > safeLimit;
        List<SessionMessageEntity> page = hasMore ? messages.subList(0, safeLimit) : messages;

        String nextCursor = page.isEmpty() ? cursor : page.get(page.size() - 1).getId();

        List<OpenSessionMessageDTO> dtos = page.stream()
                .map(m -> toOpenSessionMessageDTO(m, contextId))
                .toList();

        return RX.ok(OpenSessionMessagesResponse.builder()
                .contextId(contextId)
                .messages(dtos)
                .nextCursor(nextCursor)
                .hasMore(hasMore)
                .build());
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
            log.warn("Failed to list CLI processes for worker {}: {}", workerId, e.getMessage());
            return RX.failA("获取 CLI 进程列表失败: " + e.getMessage());
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
        try {
            Map<String, Object> result = client.killCliProcess(pid, force)
                    .block(Duration.ofSeconds(10));
            log.info("Open API killed CLI process: workerId={}, pid={}, force={}", workerId, pid, force);
            return RX.ok(result);
        } catch (Exception e) {
            log.warn("Failed to kill CLI process {} for worker {}: {}", pid, workerId, e.getMessage());
            return RX.failA("终止 CLI 进程失败: " + e.getMessage());
        }
    }

    // ===== 内部工具方法 =====

    /**
     * 验证 Agent 存在并返回（Open API 上下文）
     */
    private A2aAgent resolveOpenApiAgent(String agentId, String tenantId) {
        return agentResolver.resolveAgent(agentId, AgentResolveContext.builder()
                        .tenantId(tenantId).requestSource("OPEN_API").build())
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
        if (internalStatus == null) return "UNKNOWN";
        return switch (internalStatus) {
            case "PENDING" -> "SUBMITTED";
            case "RUNNING" -> "RUNNING";
            case "COMPLETED" -> "COMPLETED";
            case "FAILED" -> "FAILED";
            case "ABORTED" -> "CANCELLED";
            case "AWAITING_PERMISSION" -> "AWAITING_INPUT";
            default -> internalStatus;
        };
    }

    /**
     * A2aTaskState → Open API 外部状态
     */
    private String mapA2aState(A2aTaskState state) {
        if (state == null) return "UNKNOWN";
        return switch (state) {
            case SUBMITTED -> "SUBMITTED";
            case WORKING -> "RUNNING";
            case INPUT_REQUIRED -> "AWAITING_INPUT";
            case COMPLETED -> "COMPLETED";
            case FAILED -> "FAILED";
            case CANCELED -> "CANCELLED";
        };
    }

    /**
     * A2aTask → OpenApiTaskDTO 转换（简化面向第三方的响应）
     */
    private OpenApiTaskDTO toOpenApiTaskDTO(A2aTask task, String agentId) {
        OpenApiTaskDTO.OpenApiTaskDTOBuilder builder = OpenApiTaskDTO.builder()
                .taskId(task.getId())
                .agentId(agentId)
                .contextId(task.getContextId());

        if (task.getStatus() != null) {
            builder.status(mapA2aState(task.getStatus().getState()));
            if (task.getStatus().getState() == A2aTaskState.FAILED) {
                builder.errorMessage(task.getStatus().getDescription());
            }
        }

        // 提取 artifacts 中的结果文本
        if (task.getArtifacts() != null) {
            for (A2aArtifact artifact : task.getArtifacts()) {
                if (artifact.getParts() != null) {
                    for (A2aPart part : artifact.getParts()) {
                        if ("text".equals(part.getType()) && part.getText() != null) {
                            builder.result(part.getText());
                            break;
                        }
                    }
                }
            }
        }

        // 提取元信息
        if (task.getMetadata() != null) {
            Object durationMs = task.getMetadata().get("durationMs");
            if (durationMs instanceof Number) {
                builder.durationMs(((Number) durationMs).longValue());
            }
            Object costUsd = task.getMetadata().get("costUsd");
            if (costUsd instanceof BigDecimal) {
                builder.costUsd((BigDecimal) costUsd);
            } else if (costUsd instanceof Number) {
                builder.costUsd(BigDecimal.valueOf(((Number) costUsd).doubleValue()));
            }
        }

        return builder.build();
    }

    // ── 上游接入首版辅助方法 ──

    /**
     * SessionMessageEntity → OpenSessionMessageDTO
     */
    private OpenSessionMessageDTO toOpenSessionMessageDTO(SessionMessageEntity entity, String contextId) {
        Map<String, Object> metadata = null;
        if (entity.getMetadata() != null && !entity.getMetadata().isBlank()) {
            try {
                metadata = objectMapper.readValue(entity.getMetadata(),
                        new TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                log.debug("Failed to parse message metadata: {}", e.getMessage());
            }
        }

        // 推断消息类型
        String type = inferMessageType(entity.getRole(), metadata);
        String terminalStatus = inferTerminalStatus(metadata);

        // 过滤内部字段，避免泄露（不直接 mutate Jackson 反序列化的 Map）
        if (metadata != null) {
            metadata = new java.util.LinkedHashMap<>(metadata);
            metadata.remove("taskId");  // taskId 已在顶层字段返回
        }

        return OpenSessionMessageDTO.builder()
                .messageId(entity.getId())
                .contextId(contextId)
                .taskId(entity.getTaskId())
                .role(entity.getRole() != null ? entity.getRole().toLowerCase() : null)
                .type(type)
                .content(entity.getContent())
                .terminal(terminalStatus != null)
                .terminalStatus(terminalStatus)
                .metadata(metadata)
                .createdAt(entity.getCreatedAt())
                .build();
    }

    /**
     * 根据 role 和 metadata 推断对外消息类型
     */
    private String inferMessageType(String role, Map<String, Object> metadata) {
        if ("USER".equalsIgnoreCase(role)) return "USER";
        if ("SYSTEM".equalsIgnoreCase(role)) return "STATE";

        // assistant/tool 消息，从 metadata.type 推断
        if (metadata != null) {
            Object rawType = metadata.get("type");
            if (rawType instanceof String typeStr) {
                return switch (typeStr) {
                    case "TEXT_COMPLETE" -> "TEXT";
                    case "TOOL_CALL_START" -> "TOOL_CALL";
                    case "TOOL_CALL_RESULT", "TOOL_CALL_ERROR" -> "TOOL_RESULT";
                    case "TASK_COMPLETED" -> "RESULT";
                    case "STATE_SYNC" -> "STATE";
                    case "ERROR" -> "ERROR";
                    default -> "TEXT";
                };
            }
        }
        return "TEXT";
    }

    /**
     * 标记任务终态消息，供上游轮询 messages 时判断是否可停止。
     */
    private String inferTerminalStatus(Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }
        Object rawType = metadata.get("type");
        if (!(rawType instanceof String typeStr)) {
            return null;
        }
        return switch (typeStr) {
            case "TASK_COMPLETED" -> "COMPLETED";
            case "ERROR" -> "FAILED";
            default -> null;
        };
    }

    /**
     * AgentConversationContextEntity → OpenSessionSummaryDTO
     *
     * @param latestTaskMap 预取的 sessionId → latestTaskId 映射（消除 N+1）
     */
    private OpenSessionSummaryDTO toSessionSummary(AgentConversationContextEntity ctx,
                                                    String agentId,
                                                    Map<String, String> latestTaskMap) {
        String latestTaskId = ctx.getNavigatorSessionId() != null
                ? latestTaskMap.get(ctx.getNavigatorSessionId())
                : null;

        return OpenSessionSummaryDTO.builder()
                .contextId(ctx.getContextId())
                .agentId(agentId)
                .title(ctx.getContextAlias())
                .status("ACTIVE")
                .latestTaskId(latestTaskId)
                .createdAt(ctx.getCreatedAt())
                .updatedAt(ctx.getLastAccessedAt())
                .build();
    }

    /**
     * 从 sessionId 反查 contextId（通过 navigatorSessionId 映射）
     */
    private String resolveContextIdFromSession(String sessionId) {
        if (sessionId == null) return null;
        return sessionQueryService.resolveContextId(sessionId).orElse(null);
    }
}
