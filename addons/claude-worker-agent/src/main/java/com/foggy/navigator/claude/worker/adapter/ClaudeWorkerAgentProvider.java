package com.foggy.navigator.claude.worker.adapter;

import com.foggy.navigator.common.entity.WorkingDirectoryEntity;
import com.foggy.navigator.claude.worker.repository.CodingAgentRepository;
import com.foggy.navigator.common.repository.WorkingDirectoryRepository;
import com.foggy.navigator.claude.worker.service.ClaudeTaskService;
import com.foggy.navigator.common.dto.a2a.A2aAgentCard;
import com.foggy.navigator.common.dto.a2a.A2aAgentSkill;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.spi.agent.A2aAgent;
import com.foggy.navigator.spi.agent.A2aAgentProvider;
import com.foggy.navigator.spi.agent.AgentContextStore;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import com.foggy.navigator.spi.claude.ClaudeWorkerFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

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

    @Autowired
    @Qualifier("a2aAsyncExecutor")
    private Executor a2aAsyncExecutor;

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
                .or(() -> agentRepository.findByNameAndUserId(agentId, userId))
                // workerId fallback: 前端发 workerId，按关联的 CodingAgentEntity 解析
                .or(() -> agentRepository.findByWorkerIdAndUserId(agentId, userId).stream()
                        .filter(e -> "LOCAL_CLAUDE_WORKER".equals(e.getAgentType()))
                        .findFirst())
                .filter(e -> "LOCAL_CLAUDE_WORKER".equals(e.getAgentType()))
                .map(entity -> {
                    String cwd = resolveDefaultCwd(entity);
                    return new ClaudeWorkerA2aAgent(entity, taskService, workerFacade, cwd, contextStore, a2aAsyncExecutor);
                });
    }

    private String resolveDefaultCwd(CodingAgentEntity entity) {
        if (entity.getDefaultDirectoryId() == null) return null;
        return directoryRepository.findByDirectoryId(entity.getDefaultDirectoryId())
                .map(WorkingDirectoryEntity::getPath)
                .orElse(null);
    }

    // ── 上下文感知方法：自动路由 user / tenant 维度 ──

    @Override
    public List<A2aAgentCard> listAgentCards(AgentResolveContext context) {
        if (context.getTenantId() != null && "OPEN_API".equals(context.getRequestSource())) {
            return listAgentCardsByTenant(context.getTenantId());
        }
        return listAgentCards(context.getUserId());
    }

    @Override
    public Optional<A2aAgent> resolveAgent(String agentId, AgentResolveContext context) {
        if (context.getTenantId() != null && "OPEN_API".equals(context.getRequestSource())) {
            return resolveAgentByTenant(agentId, context.getTenantId());
        }
        return resolveAgent(agentId, context.getUserId());
    }

    /**
     * 租户级 Agent 列表（Open API 用，TENANT_ADMIN 可查看租户下所有 Agent）
     */
    public List<A2aAgentCard> listAgentCardsByTenant(String tenantId) {
        return agentRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                .filter(e -> "LOCAL_CLAUDE_WORKER".equals(e.getAgentType()))
                .map(this::toAgentCard)
                .toList();
    }

    /**
     * 租户级 Agent 解析（Open API 用，TENANT_ADMIN 可访问租户下任意 Agent）
     */
    /**
     * 租户级 Agent 实体查询（Open API 用，获取 userId 等信息而无需构建完整 A2aAgent 对象）
     */
    public Optional<CodingAgentEntity> getAgentEntityByTenant(String agentId, String tenantId) {
        return agentRepository.findByAgentId(agentId)
                .filter(e -> tenantId.equals(e.getTenantId()))
                .filter(e -> "LOCAL_CLAUDE_WORKER".equals(e.getAgentType()));
    }

    public Optional<A2aAgent> resolveAgentByTenant(String agentId, String tenantId) {
        return agentRepository.findByAgentId(agentId)
                .filter(e -> tenantId.equals(e.getTenantId()))
                .filter(e -> "LOCAL_CLAUDE_WORKER".equals(e.getAgentType()))
                .map(entity -> {
                    String cwd = resolveDefaultCwd(entity);
                    return new ClaudeWorkerA2aAgent(entity, taskService, workerFacade,
                            cwd, contextStore, a2aAsyncExecutor);
                });
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
                                .description("Execute coding tasks via Claude Code CLI")
                                .tags(List.of("coding", "claude-worker"))
                                .build()
                ))
                .build();
    }
}
