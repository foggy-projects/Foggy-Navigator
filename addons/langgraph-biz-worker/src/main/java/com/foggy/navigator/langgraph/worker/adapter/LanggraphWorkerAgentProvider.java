package com.foggy.navigator.langgraph.worker.adapter;

import com.foggy.navigator.common.dto.a2a.A2aAgentCard;
import com.foggy.navigator.common.dto.a2a.A2aAgentSkill;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.langgraph.worker.repository.LanggraphCodingAgentRepository;
import com.foggy.navigator.langgraph.worker.service.LanggraphTaskService;
import com.foggy.navigator.session.agent.ContextResolvingA2aAgent;
import com.foggy.navigator.spi.agent.A2aAgent;
import com.foggy.navigator.spi.agent.A2aAgentProvider;
import com.foggy.navigator.spi.agent.AgentContextStore;
import com.foggy.navigator.spi.agent.InnerA2aAgent;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Registers LangGraph Biz Worker agents into the A2A unified discovery system.
 */
@Component
@RequiredArgsConstructor
public class LanggraphWorkerAgentProvider implements A2aAgentProvider {

    private static final String AGENT_TYPE = "LOCAL_LANGGRAPH_WORKER";

    private final LanggraphCodingAgentRepository agentRepository;
    private final LanggraphTaskService taskService;
    @Nullable
    private final AgentContextStore contextStore;

    @Override
    public String getProviderType() {
        return LanggraphTaskService.PROVIDER_TYPE;
    }

    @Override
    public List<A2aAgentCard> listAgentCards(String userId) {
        return agentRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .filter(e -> AGENT_TYPE.equals(e.getAgentType()))
                .map(this::toAgentCard)
                .toList();
    }

    @Override
    public Optional<A2aAgent> resolveAgent(String agentId, String userId) {
        return agentRepository.findByAgentIdAndUserId(agentId, userId)
                .filter(e -> AGENT_TYPE.equals(e.getAgentType()))
                .map(this::toA2aAgent);
    }

    private A2aAgent toA2aAgent(CodingAgentEntity entity) {
        InnerA2aAgent inner = new LanggraphWorkerInnerA2aAgent(entity, taskService);
        // Use explicit providerType since ContextResolvingA2aAgent's auto-derive
        // doesn't know about LOCAL_LANGGRAPH_WORKER yet
        return new ContextResolvingA2aAgent(inner, contextStore, entity, LanggraphTaskService.PROVIDER_TYPE);
    }

    private A2aAgentCard toAgentCard(CodingAgentEntity entity) {
        return A2aAgentCard.builder()
                .id(entity.getAgentId())
                .name(entity.getName())
                .description(entity.getDescription())
                .version("1.0.0")
                .skills(List.of(A2aAgentSkill.builder()
                        .id("business-query")
                        .name("Business Query")
                        .description("Execute controlled business queries via Skill Runtime")
                        .build()))
                .build();
    }
}
