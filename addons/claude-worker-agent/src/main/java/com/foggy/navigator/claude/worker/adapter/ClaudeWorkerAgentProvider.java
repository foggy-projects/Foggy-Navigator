package com.foggy.navigator.claude.worker.adapter;

import com.foggy.navigator.claude.worker.model.entity.WorkingDirectoryEntity;
import com.foggy.navigator.claude.worker.repository.CodingAgentRepository;
import com.foggy.navigator.claude.worker.repository.WorkingDirectoryRepository;
import com.foggy.navigator.claude.worker.service.ClaudeTaskService;
import com.foggy.navigator.common.dto.a2a.A2aAgentCard;
import com.foggy.navigator.common.dto.a2a.A2aAgentSkill;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.spi.agent.A2aAgent;
import com.foggy.navigator.spi.agent.A2aAgentProvider;
import com.foggy.navigator.spi.agent.AgentContextStore;
import com.foggy.navigator.spi.claude.ClaudeWorkerFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * A2aAgentProvider 实现 — 将 CodingAgentEntity (LOCAL_CLAUDE_WORKER) 适配为统一 A2aAgent
 */
@Component
@RequiredArgsConstructor
public class ClaudeWorkerAgentProvider implements A2aAgentProvider {

    private final CodingAgentRepository agentRepository;
    private final ClaudeTaskService taskService;
    private final ClaudeWorkerFacade workerFacade;
    private final WorkingDirectoryRepository directoryRepository;
    @Nullable
    private final AgentContextStore contextStore;

    @Override
    public String getProviderType() {
        return "claude-worker";
    }

    @Override
    public List<A2aAgentCard> listAgentCards(String userId) {
        return agentRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .filter(e -> "LOCAL_CLAUDE_WORKER".equals(e.getAgentType()))
                .map(this::toAgentCard)
                .toList();
    }

    @Override
    public Optional<A2aAgent> resolveAgent(String agentId, String userId) {
        return agentRepository.findByAgentIdAndUserId(agentId, userId)
                .filter(e -> "LOCAL_CLAUDE_WORKER".equals(e.getAgentType()))
                .map(entity -> {
                    String cwd = resolveDefaultCwd(entity);
                    return new ClaudeWorkerA2aAgent(entity, taskService, workerFacade, cwd, contextStore);
                });
    }

    private String resolveDefaultCwd(CodingAgentEntity entity) {
        if (entity.getDefaultDirectoryId() == null) return null;
        return directoryRepository.findByDirectoryId(entity.getDefaultDirectoryId())
                .map(WorkingDirectoryEntity::getPath)
                .orElse(null);
    }

    private A2aAgentCard toAgentCard(CodingAgentEntity entity) {
        String desc = entity.getDescription();
        if (entity.getProjectSummary() != null) {
            desc = (desc != null ? desc + "\n\n" : "") + "## 项目概述\n" + entity.getProjectSummary();
        }
        return A2aAgentCard.builder()
                .name(entity.getName())
                .description(desc)
                .url(entity.getEndpointUrl())
                .version("1.0.0")
                .skills(List.of(
                        A2aAgentSkill.builder()
                                .id("coding")
                                .name("Coding")
                                .description("Execute coding tasks via Claude Code CLI")
                                .tags(List.of("coding", "claude-worker"))
                                .build()
                ))
                .build();
    }
}
