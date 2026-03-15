package com.foggy.navigator.claude.worker.controller.openapi;

import com.foggy.navigator.claude.worker.model.dto.*;
import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import com.foggy.navigator.claude.worker.model.form.*;
import com.foggy.navigator.claude.worker.repository.ClaudeWorkerRepository;
import com.foggy.navigator.claude.worker.repository.WorkingDirectoryRepository;
import com.foggy.navigator.claude.worker.service.ClaudeWorkerService;
import com.foggy.navigator.claude.worker.service.OpenApiProvisioningService;
import com.foggy.navigator.claude.worker.service.WorkerHealthChecker;
import com.foggy.navigator.claude.worker.service.WorkingDirectoryService;
import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.spi.claude.ClaudeWorkerFacade;
import com.foggyframework.core.ex.RX;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
    private final WorkingDirectoryRepository directoryRepository;
    private final WorkerHealthChecker healthChecker;

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
}
