package com.foggy.navigator.claude.worker.controller.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.business.agent.model.dto.UpstreamClientAppAdminPrincipal;
import com.foggy.navigator.business.agent.service.UpstreamBootstrapRequestService;
import com.foggy.navigator.business.agent.service.UpstreamClientAppAdminCredentialService;
import com.foggy.navigator.claude.worker.client.ClaudeWorkerClient;
import com.foggy.navigator.claude.worker.model.dto.WorkerDTO;
import com.foggy.navigator.claude.worker.model.dto.WorkingDirectoryDTO;
import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import com.foggy.navigator.claude.worker.model.form.InitDirectoryOpenForm;
import com.foggy.navigator.claude.worker.model.form.RegisterWorkerForm;
import com.foggy.navigator.claude.worker.model.form.UpdateWorkerForm;
import com.foggy.navigator.claude.worker.repository.ClaudeWorkerRepository;
import com.foggy.navigator.claude.worker.service.ClaudeWorkerService;
import com.foggy.navigator.claude.worker.service.WorkerHealthChecker;
import com.foggy.navigator.claude.worker.service.WorkingDirectoryService;
import com.foggy.navigator.common.entity.WorkingDirectoryEntity;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import com.foggy.navigator.common.enums.WorkingDirectoryResolverType;
import com.foggy.navigator.common.enums.WorkspaceScope;
import com.foggy.navigator.common.repository.WorkingDirectoryRepository;
import com.foggy.navigator.spi.claude.ClaudeWorkerFacade;
import com.foggyframework.core.ex.RX;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/upstream-admin")
@RequiredArgsConstructor
@Slf4j
public class UpstreamAdminWorkerDirectoryController {

    private final UpstreamClientAppAdminCredentialService adminCredentialService;
    private final ClaudeWorkerService workerService;
    private final WorkingDirectoryService directoryService;
    private final ClaudeWorkerFacade claudeWorkerFacade;
    private final ClaudeWorkerRepository workerRepository;
    private final WorkingDirectoryRepository directoryRepository;
    private final WorkerHealthChecker healthChecker;
    private final ObjectMapper objectMapper;

    @PostMapping("/workers")
    public RX<WorkerDTO> registerWorker(HttpServletRequest request,
                                        @RequestParam(required = false) String targetTenantId,
                                        @RequestBody RegisterWorkerForm form) {
        UpstreamClientAppAdminPrincipal principal = requireWorkerManage(request);
        String tenantId = resolveTargetTenantId(principal, request, targetTenantId);
        String actorUserId = "upstream-admin:" + principal.getCredentialId();
        WorkerDTO dto = workerService.registerWorker(actorUserId, tenantId, form);
        try {
            healthChecker.checkWorker(workerService.getWorkerEntity(dto.getWorkerId()));
        } catch (Exception e) {
            log.warn("Initial upstream-admin worker health check failed for {}: {}", dto.getWorkerId(), e.getMessage());
        }
        return RX.ok(workerService.getWorker(actorUserId, dto.getWorkerId()));
    }

    @GetMapping("/workers")
    public RX<List<WorkerDTO>> listWorkers(HttpServletRequest request,
                                           @RequestParam(required = false) String targetTenantId) {
        UpstreamClientAppAdminPrincipal principal = requireWorkerManage(request);
        String tenantId = resolveTargetTenantId(principal, request, targetTenantId);
        return RX.ok(workerRepository.findByTenantId(tenantId).stream()
                .map(entity -> workerService.getWorker(entity.getUserId(), entity.getWorkerId()))
                .toList());
    }

    @GetMapping("/workers/{workerId}")
    public RX<WorkerDTO> getWorker(HttpServletRequest request, @PathVariable String workerId) {
        requireWorkerManage(request);
        ClaudeWorkerEntity worker = resolveWorkerForAdmin(request, workerId);
        return RX.ok(workerService.getWorker(worker.getUserId(), workerId));
    }

    @PutMapping("/workers/{workerId}")
    public RX<WorkerDTO> updateWorker(HttpServletRequest request,
                                      @PathVariable String workerId,
                                      @RequestBody UpdateWorkerForm form) {
        requireWorkerManage(request);
        ClaudeWorkerEntity worker = resolveWorkerForAdmin(request, workerId);
        return RX.ok(workerService.updateWorker(worker.getUserId(), workerId, form));
    }

    @DeleteMapping("/workers/{workerId}")
    public RX<Void> deleteWorker(HttpServletRequest request, @PathVariable String workerId) {
        requireWorkerManage(request);
        ClaudeWorkerEntity worker = resolveWorkerForAdmin(request, workerId);
        workerService.deleteWorker(worker.getUserId(), workerId);
        return RX.ok(null);
    }

    @PostMapping("/workers/{workerId}/health-check")
    public RX<WorkerDTO> healthCheck(HttpServletRequest request, @PathVariable String workerId) {
        requireWorkerManage(request);
        ClaudeWorkerEntity worker = resolveWorkerForAdmin(request, workerId);
        healthChecker.checkWorker(worker);
        return RX.ok(workerService.getWorker(worker.getUserId(), workerId));
    }

    @GetMapping("/workers/{workerId}/processes")
    public RX<Map<String, Object>> listWorkerProcesses(HttpServletRequest request, @PathVariable String workerId) {
        requireWorkerManage(request);
        ClaudeWorkerEntity worker = resolveWorkerForAdmin(request, workerId);
        try {
            return RX.ok(workerService.createClient(worker).listCliProcesses().block(Duration.ofSeconds(10)));
        } catch (Exception e) {
            return RX.failA("获取 CLI 进程列表失败: " + e.getMessage());
        }
    }

    @PostMapping("/workers/{workerId}/processes/{pid}/kill")
    public RX<Map<String, Object>> killWorkerProcess(HttpServletRequest request,
                                                     @PathVariable String workerId,
                                                     @PathVariable int pid,
                                                     @RequestBody(required = false) Map<String, Object> body) {
        requireWorkerManage(request);
        ClaudeWorkerEntity worker = resolveWorkerForAdmin(request, workerId);
        boolean force = body != null && Boolean.TRUE.equals(body.get("force"));
        try {
            return RX.ok(workerService.createClient(worker).killCliProcess(pid, force).block(Duration.ofSeconds(10)));
        } catch (Exception e) {
            return RX.failA("终止 CLI 进程失败: " + e.getMessage());
        }
    }

    @PostMapping("/directories/init")
    public RX<WorkingDirectoryDTO> initDirectory(HttpServletRequest request,
                                                @RequestBody InitDirectoryOpenForm form) {
        UpstreamClientAppAdminPrincipal principal = requireDirectoryManage(request);
        if (form == null || !StringUtils.hasText(form.getWorkerId())) {
            return RX.failB("workerId is required");
        }
        if (form.getFiles() == null || form.getFiles().isEmpty()) {
            return RX.failB("files is required");
        }
        ClaudeWorkerEntity worker = resolveWorkerForPrincipal(principal, form.getWorkerId());
        try {
            rejectExistingDirectoryOwnedByOtherPrincipal(
                    principal, form.getWorkerId(), worker.getUserId(), form.getPath());
            String directoryId = claudeWorkerFacade.initDirectory(
                    worker.getUserId(), form.getWorkerId(), form.getPath(),
                    form.getFiles(), form.getProjectName());
            WorkingDirectoryEntity entity = directoryRepository.findByDirectoryId(directoryId)
                    .orElseThrow(() -> RX.throwB("Directory not found after initialization: " + directoryId));
            stampUpstreamSystemDirectory(principal, worker, entity);
            return RX.ok(toDirectoryDTO(entity));
        } catch (Exception e) {
            log.error("Upstream-admin directory init failed: {}", e.getMessage(), e);
            return RX.failA("Directory initialization failed: " + e.getMessage());
        }
    }

    @GetMapping("/directories")
    public RX<List<WorkingDirectoryDTO>> listDirectories(HttpServletRequest request,
                                                        @RequestParam(required = false) String targetTenantId,
                                                        @RequestParam(required = false) String workerId) {
        UpstreamClientAppAdminPrincipal principal = requireDirectoryManage(request);
        if (StringUtils.hasText(workerId)) {
            ClaudeWorkerEntity worker = resolveWorkerForPrincipal(principal, workerId);
            return RX.ok(directoryRepository.findByWorkerIdOrderByProjectNameAsc(workerId).stream()
                    .filter(entity -> worker.getTenantId().equals(entity.getTenantId()))
                    .filter(entity -> isOwnedByPrincipal(entity, principal))
                    .map(this::toDirectoryDTO)
                    .toList());
        }
        String tenantId = resolveTargetTenantId(principal, request, targetTenantId);
        return RX.ok(directoryRepository.findByTenantId(tenantId).stream()
                .filter(entity -> isOwnedByPrincipal(entity, principal))
                .map(this::toDirectoryDTO)
                .toList());
    }

    @GetMapping("/directories/{directoryId}")
    public RX<WorkingDirectoryDTO> getDirectory(HttpServletRequest request, @PathVariable String directoryId) {
        requireDirectoryManage(request);
        WorkingDirectoryEntity entity = resolveDirectoryForAdmin(request, directoryId);
        return RX.ok(directoryService.getDirectory(entity.getUserId(), directoryId));
    }

    @DeleteMapping("/directories/{directoryId}")
    public RX<Void> deleteDirectory(HttpServletRequest request, @PathVariable String directoryId) {
        requireDirectoryManage(request);
        WorkingDirectoryEntity entity = resolveDirectoryForAdmin(request, directoryId);
        directoryService.deleteDirectory(entity.getUserId(), directoryId);
        return RX.ok(null);
    }

    @PutMapping("/directories/{directoryId}/env")
    public RX<Map<String, String>> updateDirectoryEnvVars(HttpServletRequest request,
                                                         @PathVariable String directoryId,
                                                         @RequestBody Map<String, String> envVars) {
        requireDirectoryManage(request);
        WorkingDirectoryEntity entity = resolveDirectoryForAdmin(request, directoryId);
        try {
            entity.setCustomEnvVars(envVars == null || envVars.isEmpty()
                    ? null
                    : objectMapper.writeValueAsString(envVars));
            directoryRepository.save(entity);
            return RX.ok(envVars);
        } catch (Exception e) {
            return RX.failA("Failed to update env vars: " + e.getMessage());
        }
    }

    @PutMapping("/directories/{directoryId}/files")
    public RX<Map<String, Object>> updateDirectoryFiles(HttpServletRequest request,
                                                       @PathVariable String directoryId,
                                                       @RequestBody Map<String, String> files) {
        requireDirectoryManage(request);
        WorkingDirectoryEntity entity = resolveDirectoryForAdmin(request, directoryId);
        if (files == null || files.isEmpty()) {
            return RX.failB("files is required");
        }
        try {
            ClaudeWorkerEntity worker = workerRepository.findByWorkerId(entity.getWorkerId())
                    .orElseThrow(() -> RX.throwB("Worker not found: " + entity.getWorkerId()));
            ClaudeWorkerClient client = workerService.createClient(worker);
            return RX.ok(client.initDirectory(entity.getPath(), files).block(Duration.ofSeconds(30)));
        } catch (Exception e) {
            return RX.failA("Failed to update files: " + e.getMessage());
        }
    }

    private UpstreamClientAppAdminPrincipal requireWorkerManage(HttpServletRequest request) {
        return adminCredentialService.requireAccess(request, UpstreamBootstrapRequestService.SCOPE_WORKER_MANAGE);
    }

    private UpstreamClientAppAdminPrincipal requireDirectoryManage(HttpServletRequest request) {
        return adminCredentialService.requireAccess(request, UpstreamBootstrapRequestService.SCOPE_WORKING_DIRECTORY_MANAGE);
    }

    private ClaudeWorkerEntity resolveWorkerForAdmin(HttpServletRequest request, String workerId) {
        UpstreamClientAppAdminPrincipal principal = requireWorkerManage(request);
        return resolveWorkerForPrincipal(principal, workerId);
    }

    private ClaudeWorkerEntity resolveWorkerForPrincipal(UpstreamClientAppAdminPrincipal principal, String workerId) {
        ClaudeWorkerEntity worker = workerRepository.findByWorkerId(workerId)
                .orElseThrow(() -> RX.throwB("Worker not found: " + workerId));
        adminCredentialService.requireTenant(principal, worker.getTenantId());
        return worker;
    }

    private void rejectExistingDirectoryOwnedByOtherPrincipal(UpstreamClientAppAdminPrincipal principal,
                                                              String workerId,
                                                              String userId,
                                                              String path) {
        if (!StringUtils.hasText(path)) {
            return;
        }
        directoryRepository.findByWorkerIdAndPathAndUserId(workerId, path, userId)
                .ifPresent(existing -> requireDirectoryOwnedByPrincipal(principal, existing));
    }

    private WorkingDirectoryEntity resolveDirectoryForAdmin(HttpServletRequest request, String directoryId) {
        UpstreamClientAppAdminPrincipal principal = requireDirectoryManage(request);
        var entity = directoryRepository.findByDirectoryId(directoryId)
                .orElseThrow(() -> RX.throwB("Directory not found: " + directoryId));
        adminCredentialService.requireTenant(principal, entity.getTenantId());
        requireDirectoryOwnedByPrincipal(principal, entity);
        return entity;
    }

    private String resolveTargetTenantId(UpstreamClientAppAdminPrincipal principal,
                                         HttpServletRequest request,
                                         String targetTenantId) {
        String tenantId = StringUtils.hasText(targetTenantId) ? targetTenantId : request.getHeader("X-Tenant-Id");
        if (StringUtils.hasText(tenantId)) {
            adminCredentialService.requireTenant(principal, tenantId);
            return tenantId;
        }
        if (principal.getAuthorizedTenantIds() != null && principal.getAuthorizedTenantIds().size() == 1) {
            return principal.getAuthorizedTenantIds().iterator().next();
        }
        throw new SecurityException("targetTenantId is required for multi-tenant upstream admin credential");
    }

    private WorkingDirectoryDTO toDirectoryDTO(WorkingDirectoryEntity entity) {
        return WorkingDirectoryDTO.builder()
                .directoryId(entity.getDirectoryId())
                .workerId(entity.getWorkerId())
                .projectName(entity.getProjectName())
                .ownerType(entity.getOwnerType())
                .ownerId(entity.getOwnerId())
                .clientAppId(entity.getClientAppId())
                .upstreamUserId(entity.getUpstreamUserId())
                .workspaceScope(entity.getWorkspaceScope())
                .resolverType(entity.getResolverType())
                .rootRef(entity.getRootRef())
                .resolverKey(entity.getResolverKey())
                .readOnly(entity.getReadOnly())
                .quotaJson(entity.getQuotaJson())
                .retentionPolicyJson(entity.getRetentionPolicyJson())
                .concurrencyPolicyJson(entity.getConcurrencyPolicyJson())
                .enabled(entity.getEnabled())
                .path(entity.getPath())
                .directoryType(entity.getDirectoryType())
                .parentProjectId(entity.getParentProjectId())
                .worktree(entity.getWorktree())
                .sourceDirectoryId(entity.getSourceDirectoryId())
                .gitBranch(entity.getGitBranch())
                .gitRemoteUrl(entity.getGitRemoteUrl())
                .gitProvider(entity.getGitProvider())
                .gitStatus(entity.getGitStatus())
                .defaultAuthMode(entity.getDefaultAuthMode())
                .defaultBaseUrl(entity.getDefaultBaseUrl())
                .defaultModelConfigId(entity.getDefaultModelConfigId())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private void stampUpstreamSystemDirectory(UpstreamClientAppAdminPrincipal principal,
                                              ClaudeWorkerEntity worker,
                                              WorkingDirectoryEntity entity) {
        adminCredentialService.requireTenant(principal, worker.getTenantId());
        if (StringUtils.hasText(entity.getTenantId())) {
            adminCredentialService.requireTenant(principal, entity.getTenantId());
        }
        if (entity.getOwnerType() != null && !isOwnedByPrincipal(entity, principal)) {
            throw new SecurityException("directory is not owned by this upstream system");
        }
        entity.setTenantId(worker.getTenantId());
        entity.setOwnerType(ResourceOwnerType.UPSTREAM_SYSTEM);
        entity.setOwnerId(principal.getUpstreamSystemId());
        entity.setClientAppId(null);
        entity.setUpstreamUserId(null);
        entity.setWorkspaceScope(WorkspaceScope.UPSTREAM_SYSTEM_SHARED);
        if (entity.getResolverType() == null) {
            entity.setResolverType(WorkingDirectoryResolverType.DELEGATED);
        }
        if (!StringUtils.hasText(entity.getRootRef())) {
            entity.setRootRef(entity.getPath());
        }
        if (entity.getReadOnly() == null) {
            entity.setReadOnly(false);
        }
        if (entity.getEnabled() == null) {
            entity.setEnabled(true);
        }
        directoryRepository.save(entity);
    }

    private void requireDirectoryOwnedByPrincipal(UpstreamClientAppAdminPrincipal principal,
                                                  WorkingDirectoryEntity entity) {
        if (!isOwnedByPrincipal(entity, principal)) {
            throw new SecurityException("directory is not owned by this upstream system");
        }
    }

    private boolean isOwnedByPrincipal(WorkingDirectoryEntity entity,
                                       UpstreamClientAppAdminPrincipal principal) {
        return entity != null
                && entity.getOwnerType() == ResourceOwnerType.UPSTREAM_SYSTEM
                && principal != null
                && StringUtils.hasText(principal.getUpstreamSystemId())
                && principal.getUpstreamSystemId().equals(entity.getOwnerId());
    }
}
