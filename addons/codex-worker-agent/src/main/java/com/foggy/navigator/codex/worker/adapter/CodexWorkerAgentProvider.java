package com.foggy.navigator.codex.worker.adapter;

import com.foggy.navigator.codex.worker.repository.CodexAgentDirectoryBindingRepository;
import com.foggy.navigator.codex.worker.repository.CodexCodingAgentRepository;
import com.foggy.navigator.codex.worker.service.CodexTaskService;
import com.foggy.navigator.common.dto.a2a.A2aAgentCard;
import com.foggy.navigator.common.dto.a2a.A2aAgentSkill;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.spi.agent.A2aAgent;
import com.foggy.navigator.spi.agent.A2aAgentProvider;
import com.foggy.navigator.spi.agent.AgentContextStore;
import com.foggy.navigator.spi.worker.WorkerManagementFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * A2aAgentProvider 实现 — 将 CodingAgentEntity (LOCAL_CODEX_WORKER) 适配为统一 A2aAgent
 */
@Component
@RequiredArgsConstructor
public class CodexWorkerAgentProvider implements A2aAgentProvider {

    private final CodexCodingAgentRepository agentRepository;
    private final CodexAgentDirectoryBindingRepository bindingRepository;
    private final CodexTaskService taskService;
    @Nullable
    private final AgentContextStore contextStore;
    /** 用于获取目录路径（目录由 Claude Worker 管理） */
    @Nullable
    private final WorkerManagementFacade workerManagementFacade;

    @Override
    public String getProviderType() {
        return "codex-worker";
    }

    @Override
    public List<A2aAgentCard> listAgentCards(String userId) {
        return agentRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .filter(e -> "LOCAL_CODEX_WORKER".equals(e.getAgentType()))
                .map(this::toAgentCard)
                .toList();
    }

    @Override
    public Optional<A2aAgent> resolveAgent(String agentId, String userId) {
        return resolveManagedEntity(agentId, userId)
                .map(entity -> {
                    String cwd = resolveDefaultCwd(entity, userId);
                    return new CodexWorkerA2aAgent(entity, taskService, cwd, contextStore);
                });
    }

    /**
     * 通过 WorkerManagementFacade 获取目录路径（Codex 复用 Claude 管理的目录）
     */
    private String resolveDefaultCwd(CodingAgentEntity entity, String userId) {
        if (entity.getDefaultDirectoryId() == null) return null;
        if (workerManagementFacade == null) return null;
        return workerManagementFacade.getDirectoryPath(userId, entity.getDefaultDirectoryId());
    }

    private Optional<CodingAgentEntity> resolveManagedEntity(String lookupId, String userId) {
        return agentRepository.findByAgentIdAndUserId(lookupId, userId)
                .or(() -> agentRepository.findByNameAndUserId(lookupId, userId))
                .or(() -> agentRepository.findByDefaultDirectoryIdAndUserId(lookupId, userId)
                        .filter(this::isManagedAgent))
                .or(() -> bindingRepository.findByDirectoryId(lookupId).stream()
                        .map(binding -> agentRepository.findByAgentIdAndUserId(binding.getAgentId(), userId))
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .filter(this::isManagedAgent)
                        .findFirst())
                // workerId fallback: 仅在未命中 directoryId 时兜底
                .or(() -> agentRepository.findByWorkerIdAndUserId(lookupId, userId).stream()
                        .filter(this::isManagedAgent)
                        .findFirst())
                .filter(this::isManagedAgent);
    }

    private boolean isManagedAgent(CodingAgentEntity entity) {
        return "LOCAL_CODEX_WORKER".equals(entity.getAgentType());
    }

    private A2aAgentCard toAgentCard(CodingAgentEntity entity) {
        String desc = entity.getDescription();
        if (entity.getProjectSummary() != null) {
            desc = (desc != null ? desc + "\n\n" : "") + "## 项目概述\n" + entity.getProjectSummary();
        }
        return A2aAgentCard.builder()
                .id(entity.getAgentId())
                .name(entity.getName())
                .description(desc)
                .url(entity.getEndpointUrl())
                .version("1.0.0")
                .skills(List.of(
                        A2aAgentSkill.builder()
                                .id("coding")
                                .name("Coding")
                                .description("Execute coding tasks via OpenAI Codex")
                                .tags(List.of("coding", "codex-worker"))
                                .build()
                ))
                .build();
    }
}
