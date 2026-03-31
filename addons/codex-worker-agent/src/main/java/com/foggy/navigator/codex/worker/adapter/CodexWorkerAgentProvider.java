package com.foggy.navigator.codex.worker.adapter;

import com.foggy.navigator.codex.worker.repository.CodexCodingAgentRepository;
import com.foggy.navigator.codex.worker.service.CodexTaskService;
import com.foggy.navigator.common.dto.a2a.A2aAgentCard;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.common.util.AgentCardBuilder;
import com.foggy.navigator.session.agent.ContextResolvingA2aAgent;
import com.foggy.navigator.spi.agent.A2aAgent;
import com.foggy.navigator.spi.agent.A2aAgentProvider;
import com.foggy.navigator.spi.agent.AgentContextStore;
import com.foggy.navigator.spi.agent.InnerA2aAgent;
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
                .map(entity -> toA2aAgent(entity, userId));
    }

    private A2aAgent toA2aAgent(CodingAgentEntity entity, String userId) {
        String cwd = resolveDefaultCwd(entity, userId);
        InnerA2aAgent inner = new CodexWorkerInnerA2aAgent(entity, taskService, cwd);
        return new ContextResolvingA2aAgent(inner, contextStore, entity);
    }

    /**
     * 通过 WorkerManagementFacade 获取目录路径（Codex 复用 Claude 管理的目录）
     */
    private String resolveDefaultCwd(CodingAgentEntity entity, String userId) {
        if (entity.getDefaultDirectoryId() == null) return null;
        if (workerManagementFacade == null) return null;
        return workerManagementFacade.getDirectoryPath(userId, entity.getDefaultDirectoryId());
    }

    /**
     * Agent 解析：agentId 精确匹配 → name 匹配。
     * directory/binding/workerId 等间接查找已由 TaskDispatchFacade 的 directory# 机制处理。
     */
    private Optional<CodingAgentEntity> resolveManagedEntity(String lookupId, String userId) {
        return agentRepository.findByAgentIdAndUserId(lookupId, userId)
                .or(() -> agentRepository.findByNameAndUserId(lookupId, userId))
                .filter(this::isManagedAgent);
    }

    private boolean isManagedAgent(CodingAgentEntity entity) {
        return "LOCAL_CODEX_WORKER".equals(entity.getAgentType());
    }

    private A2aAgentCard toAgentCard(CodingAgentEntity entity) {
        return AgentCardBuilder.fromEntity(entity,
                "coding", "Execute coding tasks via OpenAI Codex",
                List.of("coding", "codex-worker"));
    }
}
