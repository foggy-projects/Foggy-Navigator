package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.claude.worker.model.dto.CodingAgentDTO;
import com.foggy.navigator.claude.worker.model.form.RegisterAgentForm;
import com.foggy.navigator.claude.worker.model.form.UpdateAgentForm;
import com.foggy.navigator.claude.worker.repository.AgentDirectoryBindingRepository;
import com.foggy.navigator.claude.worker.repository.CodingAgentRepository;
import com.foggy.navigator.common.repository.WorkingDirectoryRepository;
import com.foggy.navigator.common.entity.AgentDirectoryBindingEntity;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import com.foggy.navigator.common.entity.WorkingDirectoryEntity;
import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.spi.claude.ClaudeWorkerFacade;
import com.foggy.navigator.spi.config.LlmModelManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 编程 Agent 管理
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CodingAgentService {

    private final CodingAgentRepository agentRepository;
    private final AgentDirectoryBindingRepository bindingRepository;
    private final ClaudeWorkerService workerService;
    private final WorkingDirectoryRepository directoryRepository;
    private final ClaudeWorkerFacade claudeWorkerFacade;
    private final LlmModelManager llmModelManager;

    /**
     * 注册新 Agent
     */
    @Transactional
    public CodingAgentDTO registerAgent(String userId, String tenantId, RegisterAgentForm form) {
        if (form.getName() == null || form.getName().isBlank()) {
            throw new IllegalArgumentException("Agent name is required");
        }

        String agentType = form.getAgentType() != null ? form.getAgentType() : "LOCAL_CLAUDE_WORKER";

        // 验证 LOCAL_CLAUDE_WORKER 必须有 workerId 和 defaultDirectoryId
        if ("LOCAL_CLAUDE_WORKER".equals(agentType)) {
            if (form.getWorkerId() == null || form.getWorkerId().isBlank()) {
                throw new IllegalArgumentException("workerId is required for LOCAL_CLAUDE_WORKER agent");
            }
            if (form.getDefaultDirectoryId() == null || form.getDefaultDirectoryId().isBlank()) {
                throw new IllegalArgumentException("defaultDirectoryId is required for LOCAL_CLAUDE_WORKER agent");
            }
            // 验证 Worker 归属
            ClaudeWorkerEntity worker = workerService.getWorkerEntity(form.getWorkerId());
            if (!worker.getUserId().equals(userId)) {
                throw new IllegalArgumentException("Worker not found: " + form.getWorkerId());
            }
            // 验证目录归属
            directoryRepository.findByDirectoryIdAndUserId(form.getDefaultDirectoryId(), userId)
                    .orElseThrow(() -> new IllegalArgumentException("Directory not found: " + form.getDefaultDirectoryId()));
        }

        // 验证 LOCAL_CODEX_WORKER 必须有 workerId，目录复用 Claude Worker 管理的目录
        if ("LOCAL_CODEX_WORKER".equals(agentType)) {
            if (form.getWorkerId() == null || form.getWorkerId().isBlank()) {
                throw new IllegalArgumentException("workerId is required for LOCAL_CODEX_WORKER agent");
            }
            // 通过 ClaudeWorkerFacade 验证 Worker 存在且属于该用户（codex 配置挂在 Claude Worker 上）
            claudeWorkerFacade.validateWorkerOwnership(userId, form.getWorkerId());
            // defaultDirectoryId 可选，复用 Claude Worker 的目录
            if (form.getDefaultDirectoryId() != null && !form.getDefaultDirectoryId().isBlank()) {
                directoryRepository.findByDirectoryIdAndUserId(form.getDefaultDirectoryId(), userId)
                        .orElseThrow(() -> new IllegalArgumentException("Directory not found: " + form.getDefaultDirectoryId()));
            }
        }

        CodingAgentEntity entity = new CodingAgentEntity();
        entity.setAgentId(UUID.randomUUID().toString().substring(0, 12));
        entity.setUserId(userId);
        entity.setTenantId(tenantId);
        entity.setName(form.getName());
        entity.setDescription(form.getDescription());
        entity.setAgentType(agentType);
        entity.setWorkerId(form.getWorkerId());
        entity.setDefaultDirectoryId(form.getDefaultDirectoryId());
        entity.setSkills(form.getSkills());
        entity.setDefaultBranch(form.getDefaultBranch());
        entity.setDefaultModelConfigId(form.getDefaultModelConfigId());
        agentRepository.save(entity);

        // 自动绑定 defaultDirectory
        if (form.getDefaultDirectoryId() != null) {
            AgentDirectoryBindingEntity binding = new AgentDirectoryBindingEntity();
            binding.setAgentId(entity.getAgentId());
            binding.setDirectoryId(form.getDefaultDirectoryId());
            bindingRepository.save(binding);
        }

        log.info("Agent registered: agentId={}, name={}, type={}", entity.getAgentId(), form.getName(), agentType);
        return toDTO(entity);
    }

    /**
     * 更新 Agent 配置
     */
    @Transactional
    public CodingAgentDTO updateAgent(String userId, String agentId, UpdateAgentForm form) {
        CodingAgentEntity entity = agentRepository.findByAgentIdAndUserId(agentId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentId));

        if (form.getName() != null) {
            entity.setName(form.getName());
        }
        if (form.getDescription() != null) {
            entity.setDescription(form.getDescription().isEmpty() ? null : form.getDescription());
        }
        if (form.getSkills() != null) {
            entity.setSkills(form.getSkills().isEmpty() ? null : form.getSkills());
        }
        if (form.getDefaultBranch() != null) {
            entity.setDefaultBranch(form.getDefaultBranch().isEmpty() ? null : form.getDefaultBranch());
        }
        if (form.getProjectSummary() != null) {
            entity.setProjectSummary(form.getProjectSummary().isEmpty() ? null : form.getProjectSummary());
        }
        if (form.getDefaultDirectoryId() != null) {
            if (form.getDefaultDirectoryId().isEmpty()) {
                entity.setDefaultDirectoryId(null);
            } else {
                directoryRepository.findByDirectoryIdAndUserId(form.getDefaultDirectoryId(), userId)
                        .orElseThrow(() -> new IllegalArgumentException("Directory not found: " + form.getDefaultDirectoryId()));
                entity.setDefaultDirectoryId(form.getDefaultDirectoryId());
            }
        }
        if (form.getDefaultModelConfigId() != null) {
            if (form.getDefaultModelConfigId().isEmpty()) {
                entity.setDefaultModelConfigId(null);
            } else {
                entity.setDefaultModelConfigId(form.getDefaultModelConfigId());
            }
        }

        agentRepository.save(entity);
        log.info("Agent updated: agentId={}", agentId);
        return toDTO(entity);
    }

    /**
     * 删除 Agent
     */
    @Transactional
    public void deleteAgent(String userId, String agentId) {
        CodingAgentEntity entity = agentRepository.findByAgentIdAndUserId(agentId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentId));
        bindingRepository.deleteByAgentId(agentId);
        agentRepository.delete(entity);
        log.info("Agent deleted: agentId={}", agentId);
    }

    /**
     * 获取 Agent 详情
     */
    public CodingAgentDTO getAgent(String userId, String agentId) {
        CodingAgentEntity entity = agentRepository.findByAgentIdAndUserId(agentId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentId));
        return toDTO(entity);
    }

    /**
     * 列出用户的所有 Agent
     */
    public List<CodingAgentDTO> listAgents(String userId) {
        return agentRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toDTO)
                .toList();
    }

    /**
     * 绑定目录到 Agent
     */
    @Transactional
    public void bindDirectory(String userId, String agentId, String directoryId) {
        CodingAgentEntity agent = agentRepository.findByAgentIdAndUserId(agentId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentId));
        directoryRepository.findByDirectoryIdAndUserId(directoryId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Directory not found: " + directoryId));

        // 检查重复
        if (bindingRepository.findByAgentIdAndDirectoryId(agentId, directoryId).isPresent()) {
            return; // 已绑定，幂等
        }

        AgentDirectoryBindingEntity binding = new AgentDirectoryBindingEntity();
        binding.setAgentId(agentId);
        binding.setDirectoryId(directoryId);
        bindingRepository.save(binding);
        log.info("Directory bound to agent: agentId={}, directoryId={}", agentId, directoryId);
    }

    /**
     * 解绑目录
     */
    @Transactional
    public void unbindDirectory(String userId, String agentId, String directoryId) {
        agentRepository.findByAgentIdAndUserId(agentId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentId));
        bindingRepository.deleteByAgentIdAndDirectoryId(agentId, directoryId);
        log.info("Directory unbound from agent: agentId={}, directoryId={}", agentId, directoryId);
    }

    /**
     * 获取 Agent 授权的目录列表
     */
    public List<CodingAgentDTO.DirectorySummary> getAgentDirectories(String userId, String agentId) {
        agentRepository.findByAgentIdAndUserId(agentId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentId));
        List<AgentDirectoryBindingEntity> bindings = bindingRepository.findByAgentId(agentId);
        return bindings.stream()
                .map(b -> directoryRepository.findByDirectoryIdAndUserId(b.getDirectoryId(), userId).orElse(null))
                .filter(d -> d != null)
                .map(this::toDirectorySummary)
                .toList();
    }

    /**
     * 获取 Agent 实体（内部使用）
     */
    public CodingAgentEntity getAgentEntity(String agentId) {
        return agentRepository.findByAgentId(agentId)
                .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentId));
    }

    /**
     * 自动生成项目概述 — 通过 Claude Worker 分析项目目录
     */
    @Transactional
    public CodingAgentDTO generateSummary(String userId, String agentId, String hint) {
        CodingAgentEntity entity = agentRepository.findByAgentIdAndUserId(agentId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentId));

        if (entity.getWorkerId() == null) {
            throw new IllegalArgumentException("Agent has no associated worker");
        }

        String cwd = resolveDefaultCwd(entity);
        String prompt = "分析当前项目目录结构和代码，生成结构化的项目概述。" +
                "包含：技术栈、主要模块/服务、对外API端点、关键依赖。" +
                "以简洁的文本格式输出，控制在500字以内。" +
                (hint != null ? "\n补充信息：" + hint : "");

        Map<String, Object> result = claudeWorkerFacade.syncQuery(
                userId, entity.getWorkerId(), prompt, cwd, null, 1, null);

        String summary = (String) result.get("resultText");
        if (summary != null && !summary.isBlank()) {
            entity.setProjectSummary(summary);
            agentRepository.save(entity);
            log.info("Project summary generated for agent: agentId={}", agentId);
        }
        return toDTO(entity);
    }

    String resolveDefaultCwd(CodingAgentEntity entity) {
        if (entity.getDefaultDirectoryId() == null) return null;
        return directoryRepository.findByDirectoryId(entity.getDefaultDirectoryId())
                .map(WorkingDirectoryEntity::getPath)
                .orElse(null);
    }

    private CodingAgentDTO toDTO(CodingAgentEntity entity) {
        // 查询 worker 名称
        String workerName = null;
        if (entity.getWorkerId() != null) {
            try {
                workerName = workerService.getWorkerEntity(entity.getWorkerId()).getName();
            } catch (Exception ignored) {}
        }

        // 查询默认目录信息
        CodingAgentDTO.DirectorySummary defaultDir = null;
        if (entity.getDefaultDirectoryId() != null) {
            defaultDir = directoryRepository.findByDirectoryId(entity.getDefaultDirectoryId())
                    .map(this::toDirectorySummary)
                    .orElse(null);
        }

        // 查询授权目录
        List<CodingAgentDTO.DirectorySummary> authorizedDirs = bindingRepository.findByAgentId(entity.getAgentId())
                .stream()
                .map(b -> directoryRepository.findByDirectoryId(b.getDirectoryId()).orElse(null))
                .filter(d -> d != null)
                .map(this::toDirectorySummary)
                .toList();

        // 查询默认 LLM 模型配置名称
        String modelConfigName = null;
        if (entity.getDefaultModelConfigId() != null) {
            modelConfigName = llmModelManager.getModelConfig(entity.getDefaultModelConfigId())
                    .map(LlmModelConfigDTO::getName).orElse(null);
        }

        return CodingAgentDTO.builder()
                .agentId(entity.getAgentId())
                .name(entity.getName())
                .description(entity.getDescription())
                .agentType(entity.getAgentType())
                .workerId(entity.getWorkerId())
                .workerName(workerName)
                .defaultDirectoryId(entity.getDefaultDirectoryId())
                .skills(entity.getSkills())
                .defaultBranch(entity.getDefaultBranch())
                .projectSummary(entity.getProjectSummary())
                .defaultModelConfigId(entity.getDefaultModelConfigId())
                .defaultModelConfigName(modelConfigName)
                .defaultDirectory(defaultDir)
                .authorizedDirectories(authorizedDirs)
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private CodingAgentDTO.DirectorySummary toDirectorySummary(WorkingDirectoryEntity dir) {
        return CodingAgentDTO.DirectorySummary.builder()
                .directoryId(dir.getDirectoryId())
                .projectName(dir.getProjectName())
                .path(dir.getPath())
                .gitBranch(dir.getGitBranch())
                .build();
    }
}
