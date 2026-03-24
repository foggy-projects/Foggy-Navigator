package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.claude.worker.model.dto.ExternalUserDTO;
import com.foggy.navigator.claude.worker.model.dto.OpenApiRegisterResultDTO;
import com.foggy.navigator.claude.worker.model.dto.ProvisionResultDTO;
import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import com.foggy.navigator.claude.worker.model.entity.ExternalUserMappingEntity;
import com.foggy.navigator.claude.worker.model.form.OpenApiRegisterForm;
import com.foggy.navigator.claude.worker.model.form.ProvisionEmployeeForm;
import com.foggy.navigator.common.entity.WorkingDirectoryEntity;
import com.foggy.navigator.claude.worker.repository.ClaudeWorkerRepository;
import com.foggy.navigator.claude.worker.repository.CodingAgentRepository;
import com.foggy.navigator.claude.worker.repository.ExternalUserMappingRepository;
import com.foggy.navigator.common.repository.WorkingDirectoryRepository;
import com.foggy.navigator.common.dto.ApiKeyDTO;
import com.foggy.navigator.common.dto.UserDTO;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.common.form.ApiKeyCreateForm;
import com.foggy.navigator.common.form.UserRegisterForm;
import com.foggy.navigator.common.util.IdGenerator;
import com.foggy.navigator.spi.auth.UserAuthService;
import com.foggy.navigator.spi.claude.ClaudeWorkerFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.List;
import java.util.Optional;

/**
 * Open API Provisioning 业务逻辑
 * 处理第三方系统注册、员工 Provisioning 等复合操作
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpenApiProvisioningService {

    private final UserAuthService userAuthService;
    private final ClaudeWorkerFacade claudeWorkerFacade;
    private final ClaudeWorkerRepository workerRepository;
    private final CodingAgentRepository agentRepository;
    private final WorkingDirectoryService directoryService;
    private final WorkingDirectoryRepository directoryRepository;
    private final ExternalUserMappingRepository mappingRepository;

    private static final SecureRandom RANDOM = new SecureRandom();

    // ===== 第三方系统自助注册 =====

    /**
     * 第三方系统自助注册：创建租户 + 管理员用户 + API Key
     */
    @Transactional
    public OpenApiRegisterResultDTO register(OpenApiRegisterForm form) {
        // 参数校验
        if (form.getSystemName() == null || form.getSystemName().isBlank()) {
            throw new IllegalArgumentException("systemName is required");
        }
        if (form.getAdminUsername() == null || form.getAdminUsername().isBlank()) {
            throw new IllegalArgumentException("adminUsername is required");
        }
        if (form.getAdminPassword() == null || form.getAdminPassword().isBlank()) {
            throw new IllegalArgumentException("adminPassword is required");
        }

        // 生成 tenantId：tenant-{systemName}-{shortId}
        String tenantId = "tenant-" + form.getSystemName().toLowerCase().replaceAll("[^a-z0-9]", "")
                + "-" + IdGenerator.shortId().substring(9); // 取 shortId 的后 4 位

        // 创建管理员用户
        UserRegisterForm registerForm = new UserRegisterForm();
        registerForm.setTenantId(tenantId);
        registerForm.setUsername(form.getAdminUsername());
        registerForm.setPassword(form.getAdminPassword());
        registerForm.setDisplayName(form.getSystemName() + " Admin");
        registerForm.setRoles("TENANT_ADMIN");

        String userId = userAuthService.registerUser(registerForm);

        // 生成 API Key
        ApiKeyCreateForm apiKeyForm = new ApiKeyCreateForm();
        apiKeyForm.setName(form.getSystemName() + " Integration Key");
        ApiKeyDTO apiKeyDTO = userAuthService.createApiKey(userId, apiKeyForm);

        log.info("Open API registration completed: systemName={}, tenantId={}, userId={}, username={}",
                form.getSystemName(), tenantId, userId, form.getAdminUsername());

        return OpenApiRegisterResultDTO.builder()
                .tenantId(tenantId)
                .userId(userId)
                .username(form.getAdminUsername())
                .apiKey(apiKeyDTO.getApiKey())
                .build();
    }

    // ===== 员工 Provisioning =====

    /**
     * 一站式 Provisioning：创建员工用户 + 工作目录 + 初始化文件
     * 幂等：重复调用相同 externalUserId 返回已有数据
     */
    @Transactional
    public ProvisionResultDTO provisionEmployee(String callerTenantId, ProvisionEmployeeForm form) {
        // 参数校验
        validateProvisionForm(form);

        // 1. 验证 Worker 归属（必须属于同一租户）
        ClaudeWorkerEntity worker = workerRepository.findByWorkerId(form.getWorkerId())
                .orElseThrow(() -> new IllegalArgumentException("Worker not found: " + form.getWorkerId()));
        if (!callerTenantId.equals(worker.getTenantId())) {
            throw new IllegalArgumentException("Worker not found: " + form.getWorkerId());
        }

        // 2. 解析或创建 Navigator 用户
        boolean userCreated = false;
        String userId;
        String username;
        String passwordToReturn = null;

        Optional<ExternalUserMappingEntity> existingMapping = mappingRepository
                .findByTenantIdAndExternalUserId(callerTenantId, form.getExternalUserId());

        if (existingMapping.isPresent()) {
            // 用户已存在
            userId = existingMapping.get().getUserId();
            Optional<UserDTO> userDTO = userAuthService.getUser(userId);
            username = userDTO.map(UserDTO::getUsername).orElse("unknown");
        } else {
            // 创建新用户
            username = callerTenantId + "_" + form.getExternalUserId();
            String password = (form.getPassword() != null && !form.getPassword().isBlank())
                    ? form.getPassword() : generateRandomPassword();

            UserRegisterForm registerForm = new UserRegisterForm();
            registerForm.setTenantId(callerTenantId);
            registerForm.setUsername(username);
            registerForm.setPassword(password);
            registerForm.setDisplayName(form.getDisplayName());
            registerForm.setRoles("DEVELOPER");

            userId = userAuthService.registerUser(registerForm);

            // 保存外部用户映射
            ExternalUserMappingEntity mapping = new ExternalUserMappingEntity();
            mapping.setId(IdGenerator.shortId());
            mapping.setTenantId(callerTenantId);
            mapping.setExternalUserId(form.getExternalUserId());
            mapping.setExternalDisplayName(form.getDisplayName());
            mapping.setUserId(userId);
            mappingRepository.save(mapping);

            userCreated = true;
            passwordToReturn = password;
            log.info("Created employee user: externalUserId={}, userId={}, username={}",
                    form.getExternalUserId(), userId, username);
        }

        // 3. 检查目录是否已存在（按 workerId + path 查重，幂等）
        String directoryId;
        boolean directoryCreated;
        Optional<WorkingDirectoryEntity> existingDir = directoryRepository
                .findByWorkerIdAndPath(form.getWorkerId(), form.getDirectoryPath());

        if (existingDir.isPresent()) {
            // 目录已存在，确保所有者是该员工
            directoryId = existingDir.get().getDirectoryId();
            directoryCreated = false;
            if (!userId.equals(existingDir.get().getUserId())) {
                directoryService.updateDirectoryOwner(directoryId, userId);
            }
            log.info("Directory already exists, reusing: directoryId={}, path={}",
                    directoryId, form.getDirectoryPath());
        } else {
            // 新建目录（使用 Worker 所有者的 userId 调用，因为 Worker 归属检查基于 userId）
            directoryId = claudeWorkerFacade.initDirectory(
                    worker.getUserId(), form.getWorkerId(), form.getDirectoryPath(),
                    form.getFiles(), form.getProjectName());
            // 更新目录所有者为员工
            directoryService.updateDirectoryOwner(directoryId, userId);
            directoryCreated = true;
        }

        // 4. 自动注册 A2A Agent（幂等：同一 userId + directoryId 不重复创建）
        String agentId = ensureAgentForEmployee(userId, worker.getWorkerId(), directoryId,
                form.getExternalUserId(), form.getProjectName(), callerTenantId);

        return ProvisionResultDTO.builder()
                .externalUserId(form.getExternalUserId())
                .userId(userId)
                .username(username)
                .password(passwordToReturn)
                .directoryId(directoryId)
                .directoryPath(form.getDirectoryPath())
                .agentId(agentId)
                .userCreated(userCreated)
                .directoryCreated(directoryCreated)
                .build();
    }

    // ===== 外部用户查询 =====

    /**
     * 列出租户下所有已映射的外部用户
     */
    public List<ExternalUserDTO> listExternalUsers(String tenantId) {
        return mappingRepository.findByTenantId(tenantId).stream()
                .map(this::toExternalUserDTO)
                .toList();
    }

    /**
     * 查询单个外部用户映射
     */
    public Optional<ExternalUserDTO> getExternalUser(String tenantId, String externalUserId) {
        return mappingRepository.findByTenantIdAndExternalUserId(tenantId, externalUserId)
                .map(this::toExternalUserDTO);
    }

    /**
     * 删除外部用户映射（不删除 Navigator 用户和工作目录）
     */
    @Transactional
    public void deleteExternalUserMapping(String tenantId, String externalUserId) {
        ExternalUserMappingEntity mapping = mappingRepository
                .findByTenantIdAndExternalUserId(tenantId, externalUserId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "External user mapping not found: " + externalUserId));
        mappingRepository.delete(mapping);
        log.info("External user mapping deleted: tenantId={}, externalUserId={}", tenantId, externalUserId);
    }

    // ===== 内部方法 =====

    /**
     * 确保员工有对应的 A2A Agent（幂等：同一 userId + directoryId 不重复创建）
     */
    private String ensureAgentForEmployee(String userId, String workerId, String directoryId,
                                           String externalUserId, String projectName, String tenantId) {
        // 检查是否已有对应的 Agent
        return agentRepository.findByDefaultDirectoryIdAndUserId(directoryId, userId)
                .map(CodingAgentEntity::getAgentId)
                .orElseGet(() -> {
                    CodingAgentEntity agent = new CodingAgentEntity();
                    agent.setAgentId(IdGenerator.shortId());
                    agent.setUserId(userId);
                    agent.setTenantId(tenantId);
                    agent.setWorkerId(workerId);
                    agent.setDefaultDirectoryId(directoryId);
                    agent.setName(externalUserId + "-agent");
                    agent.setAgentType("LOCAL_CLAUDE_WORKER");
                    agent.setDescription(projectName != null ? projectName : externalUserId + " Agent");
                    agentRepository.save(agent);
                    log.info("Auto-registered A2A Agent for employee: agentId={}, userId={}, directoryId={}",
                            agent.getAgentId(), userId, directoryId);
                    return agent.getAgentId();
                });
    }

    private ExternalUserDTO toExternalUserDTO(ExternalUserMappingEntity entity) {
        String username = userAuthService.getUser(entity.getUserId())
                .map(UserDTO::getUsername)
                .orElse("unknown");

        return ExternalUserDTO.builder()
                .externalUserId(entity.getExternalUserId())
                .externalDisplayName(entity.getExternalDisplayName())
                .userId(entity.getUserId())
                .username(username)
                .createdAt(entity.getCreatedAt())
                .build();
    }

    private void validateProvisionForm(ProvisionEmployeeForm form) {
        if (form.getExternalUserId() == null || form.getExternalUserId().isBlank()) {
            throw new IllegalArgumentException("externalUserId is required");
        }
        if (form.getWorkerId() == null || form.getWorkerId().isBlank()) {
            throw new IllegalArgumentException("workerId is required");
        }
        if (form.getDirectoryPath() == null || form.getDirectoryPath().isBlank()) {
            throw new IllegalArgumentException("directoryPath is required");
        }
        if (form.getFiles() == null || form.getFiles().isEmpty()) {
            throw new IllegalArgumentException("files is required (at least CLAUDE.md)");
        }
    }

    private String generateRandomPassword() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder(10);
        for (int i = 0; i < 10; i++) {
            sb.append(chars.charAt(RANDOM.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
