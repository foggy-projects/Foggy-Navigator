package com.foggy.navigator.claude.worker.controller.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.business.agent.model.dto.ClientAppControlPlanePrincipal;
import com.foggy.navigator.business.agent.service.ClientAppControlCredentialService;
import com.foggy.navigator.claude.worker.client.ClaudeWorkerClient;
import com.foggy.navigator.claude.worker.model.dto.WorkingDirectoryDTO;
import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import com.foggy.navigator.claude.worker.model.form.ClientAppDirectoryInitForm;
import com.foggy.navigator.claude.worker.repository.ClaudeWorkerRepository;
import com.foggy.navigator.claude.worker.service.ClaudeWorkerService;
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
@RequestMapping("/api/v1/client-apps/{clientAppId}/directories")
@RequiredArgsConstructor
@Slf4j
public class ClientAppWorkingDirectoryController {

    private final ClientAppControlCredentialService controlCredentialService;
    private final ClaudeWorkerService workerService;
    private final WorkingDirectoryService directoryService;
    private final ClaudeWorkerFacade claudeWorkerFacade;
    private final ClaudeWorkerRepository workerRepository;
    private final WorkingDirectoryRepository directoryRepository;
    private final ObjectMapper objectMapper;

    @PostMapping("/init")
    public RX<WorkingDirectoryDTO> initDirectory(HttpServletRequest request,
                                                @PathVariable String clientAppId,
                                                @RequestBody ClientAppDirectoryInitForm form) {
        ClientAppControlPlanePrincipal principal = requireDirectoryManage(request, clientAppId);
        RX<WorkingDirectoryDTO> validation = validateInitForm(form);
        if (validation != null) {
            return validation;
        }
        ClaudeWorkerEntity worker = resolveWorkerForClientApp(principal, form.getWorkerId());
        try {
            rejectExistingDirectoryOwnedByOtherClientAppResource(
                    principal, form.getWorkerId(), worker.getUserId(), form.getPath());
            String directoryId = claudeWorkerFacade.initDirectory(
                    worker.getUserId(), form.getWorkerId(), form.getPath(),
                    form.getFiles(), form.getProjectName());
            WorkingDirectoryEntity entity = directoryRepository.findByDirectoryId(directoryId)
                    .orElseThrow(() -> RX.throwB("Directory not found after initialization: " + directoryId));
            stampClientAppDirectory(principal, worker, entity, form);
            return RX.ok(toDirectoryDTO(entity));
        } catch (Exception e) {
            log.error("ClientApp directory init failed: {}", e.getMessage(), e);
            return RX.failA("Directory initialization failed: " + e.getMessage());
        }
    }

    @GetMapping
    public RX<List<WorkingDirectoryDTO>> listDirectories(HttpServletRequest request,
                                                        @PathVariable String clientAppId,
                                                        @RequestParam(required = false) String workerId,
                                                        @RequestParam(required = false) WorkspaceScope workspaceScope,
                                                        @RequestParam(required = false) String upstreamUserId) {
        ClientAppControlPlanePrincipal principal = requireDirectoryManage(request, clientAppId);
        List<WorkingDirectoryEntity> candidates;
        if (StringUtils.hasText(workerId)) {
            ClaudeWorkerEntity worker = resolveWorkerForClientApp(principal, workerId);
            candidates = directoryRepository.findByWorkerIdOrderByProjectNameAsc(workerId).stream()
                    .filter(entity -> worker.getTenantId().equals(entity.getTenantId()))
                    .toList();
        } else {
            candidates = directoryRepository.findByTenantId(principal.getTenantId());
        }
        return RX.ok(candidates.stream()
                .filter(entity -> isVisibleToClientApp(principal, entity))
                .filter(entity -> workspaceScope == null || workspaceScope == entity.getWorkspaceScope())
                .filter(entity -> !StringUtils.hasText(upstreamUserId)
                        || upstreamUserId.equals(entity.getUpstreamUserId()))
                .map(this::toDirectoryDTO)
                .toList());
    }

    @GetMapping("/{directoryId}")
    public RX<WorkingDirectoryDTO> getDirectory(HttpServletRequest request,
                                               @PathVariable String clientAppId,
                                               @PathVariable String directoryId) {
        ClientAppControlPlanePrincipal principal = requireDirectoryManage(request, clientAppId);
        WorkingDirectoryEntity entity = resolveDirectoryForClientApp(principal, directoryId);
        return RX.ok(directoryService.getDirectory(entity.getUserId(), directoryId));
    }

    @DeleteMapping("/{directoryId}")
    public RX<Void> deleteDirectory(HttpServletRequest request,
                                    @PathVariable String clientAppId,
                                    @PathVariable String directoryId) {
        ClientAppControlPlanePrincipal principal = requireDirectoryManage(request, clientAppId);
        WorkingDirectoryEntity entity = resolveDirectoryForClientApp(principal, directoryId);
        directoryService.deleteDirectory(entity.getUserId(), directoryId);
        return RX.ok(null);
    }

    @PutMapping("/{directoryId}/env")
    public RX<Map<String, String>> updateDirectoryEnvVars(HttpServletRequest request,
                                                         @PathVariable String clientAppId,
                                                         @PathVariable String directoryId,
                                                         @RequestBody Map<String, String> envVars) {
        ClientAppControlPlanePrincipal principal = requireDirectoryManage(request, clientAppId);
        WorkingDirectoryEntity entity = resolveDirectoryForClientApp(principal, directoryId);
        if (Boolean.TRUE.equals(entity.getReadOnly())) {
            return RX.failB("working directory is read-only: " + directoryId);
        }
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

    @PutMapping("/{directoryId}/files")
    public RX<Map<String, Object>> updateDirectoryFiles(HttpServletRequest request,
                                                       @PathVariable String clientAppId,
                                                       @PathVariable String directoryId,
                                                       @RequestBody Map<String, String> files) {
        ClientAppControlPlanePrincipal principal = requireDirectoryManage(request, clientAppId);
        WorkingDirectoryEntity entity = resolveDirectoryForClientApp(principal, directoryId);
        if (Boolean.TRUE.equals(entity.getReadOnly())) {
            return RX.failB("working directory is read-only: " + directoryId);
        }
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

    private ClientAppControlPlanePrincipal requireDirectoryManage(HttpServletRequest request, String clientAppId) {
        return controlCredentialService.requireAccess(
                request, ClientAppControlCredentialService.SCOPE_WORKING_DIRECTORY_MANAGE, clientAppId);
    }

    private RX<WorkingDirectoryDTO> validateInitForm(ClientAppDirectoryInitForm form) {
        if (form == null) {
            return RX.failB("request body is required");
        }
        if (!StringUtils.hasText(form.getWorkerId())) {
            return RX.failB("workerId is required");
        }
        if (!StringUtils.hasText(form.getPath())) {
            return RX.failB("path is required");
        }
        if (form.getFiles() == null || form.getFiles().isEmpty()) {
            return RX.failB("files is required");
        }
        if (form.getWorkspaceScope() == null) {
            return RX.failB("workspaceScope is required");
        }
        if (form.getWorkspaceScope() == WorkspaceScope.USER_PRIVATE
                && !StringUtils.hasText(form.getUpstreamUserId())) {
            return RX.failB("upstreamUserId is required for USER_PRIVATE workspace");
        }
        if (form.getWorkspaceScope() == WorkspaceScope.CLIENT_APP_SHARED
                && StringUtils.hasText(form.getUpstreamUserId())) {
            return RX.failB("upstreamUserId is not allowed for CLIENT_APP_SHARED workspace");
        }
        if (form.getWorkspaceScope() == WorkspaceScope.UPSTREAM_SYSTEM_SHARED) {
            return RX.failB("UPSTREAM_SYSTEM_SHARED workspace must be created by upstream admin directory API");
        }
        return null;
    }

    private ClaudeWorkerEntity resolveWorkerForClientApp(ClientAppControlPlanePrincipal principal, String workerId) {
        ClaudeWorkerEntity worker = workerRepository.findByWorkerId(workerId)
                .orElseThrow(() -> RX.throwB("Worker not found: " + workerId));
        if (!principal.getTenantId().equals(worker.getTenantId())) {
            throw new SecurityException("Worker not found: " + workerId);
        }
        return worker;
    }

    private WorkingDirectoryEntity resolveDirectoryForClientApp(ClientAppControlPlanePrincipal principal,
                                                                String directoryId) {
        WorkingDirectoryEntity entity = directoryRepository.findByDirectoryId(directoryId)
                .orElseThrow(() -> RX.throwB("Directory not found: " + directoryId));
        if (!isVisibleToClientApp(principal, entity)) {
            throw new SecurityException("Directory not found: " + directoryId);
        }
        return entity;
    }

    private void rejectExistingDirectoryOwnedByOtherClientAppResource(ClientAppControlPlanePrincipal principal,
                                                                      String workerId,
                                                                      String userId,
                                                                      String path) {
        directoryRepository.findByWorkerIdAndPathAndUserId(workerId, path, userId)
                .ifPresent(existing -> {
                    if (!isVisibleToClientApp(principal, existing)) {
                        throw new SecurityException("directory path is already owned by another principal");
                    }
                });
    }

    private void stampClientAppDirectory(ClientAppControlPlanePrincipal principal,
                                         ClaudeWorkerEntity worker,
                                         WorkingDirectoryEntity entity,
                                         ClientAppDirectoryInitForm form) throws Exception {
        if (!principal.getTenantId().equals(worker.getTenantId())) {
            throw new SecurityException("worker tenant mismatch");
        }
        if (entity.getOwnerType() != null && !isVisibleToClientApp(principal, entity)) {
            throw new SecurityException("directory is not owned by this client app");
        }
        entity.setTenantId(worker.getTenantId());
        entity.setClientAppId(principal.getClientAppId());
        entity.setWorkspaceScope(form.getWorkspaceScope());
        if (form.getWorkspaceScope() == WorkspaceScope.CLIENT_APP_SHARED) {
            entity.setOwnerType(ResourceOwnerType.CLIENT_APP);
            entity.setOwnerId(principal.getClientAppId());
            entity.setUpstreamUserId(null);
        } else {
            entity.setOwnerType(ResourceOwnerType.UPSTREAM_USER);
            entity.setUpstreamUserId(form.getUpstreamUserId().trim());
            entity.setOwnerId(principal.getClientAppId() + ":" + form.getUpstreamUserId().trim());
        }
        entity.setResolverType(form.getResolverType() != null
                ? form.getResolverType()
                : WorkingDirectoryResolverType.DELEGATED);
        entity.setRootRef(StringUtils.hasText(form.getRootRef()) ? form.getRootRef() : entity.getPath());
        entity.setResolverKey(blankToNull(form.getResolverKey()));
        entity.setReadOnly(Boolean.TRUE.equals(form.getReadOnly()));
        entity.setAllowedPathPrefixesJson(form.getAllowedPathPrefixes() == null || form.getAllowedPathPrefixes().isEmpty()
                ? null
                : objectMapper.writeValueAsString(form.getAllowedPathPrefixes()));
        entity.setQuotaJson(blankToNull(form.getQuotaJson()));
        entity.setRetentionPolicyJson(blankToNull(form.getRetentionPolicyJson()));
        entity.setConcurrencyPolicyJson(blankToNull(form.getConcurrencyPolicyJson()));
        entity.setEnabled(form.getEnabled() == null || form.getEnabled());
        directoryRepository.save(entity);
    }

    private boolean isVisibleToClientApp(ClientAppControlPlanePrincipal principal, WorkingDirectoryEntity entity) {
        if (principal == null || entity == null || !principal.getTenantId().equals(entity.getTenantId())) {
            return false;
        }
        if (!principal.getClientAppId().equals(entity.getClientAppId())) {
            return false;
        }
        return (entity.getOwnerType() == ResourceOwnerType.CLIENT_APP
                && entity.getWorkspaceScope() == WorkspaceScope.CLIENT_APP_SHARED
                && principal.getClientAppId().equals(entity.getOwnerId()))
                || (entity.getOwnerType() == ResourceOwnerType.UPSTREAM_USER
                && entity.getWorkspaceScope() == WorkspaceScope.USER_PRIVATE
                && StringUtils.hasText(entity.getUpstreamUserId()));
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

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
