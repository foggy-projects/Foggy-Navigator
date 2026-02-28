package com.foggy.navigator.session.controller;

import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.a2a.*;
import com.foggy.navigator.session.registry.DefaultA2aAgentRegistry;
import com.foggy.navigator.spi.agent.A2aAgent;
import com.foggyframework.core.ex.RX;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 统一 Agent 发现 API — 只读端点，聚合所有 Provider 管理的 Agent
 */
@RestController
@RequestMapping("/api/v1/agents")
@RequireAuth
@RequiredArgsConstructor
public class AgentDiscoveryController {

    private final DefaultA2aAgentRegistry registry;

    @GetMapping
    public RX<List<A2aAgentCard>> listAgents(
            @RequestParam(required = false) String type) {
        String userId = UserContext.getCurrentUserId();
        List<A2aAgentCard> cards = (type != null)
                ? registry.listByProviderType(type, userId)
                : registry.listAgents(userId);
        return RX.ok(cards);
    }

    @GetMapping("/{agentId}/card")
    public RX<A2aAgentCard> getAgentCard(@PathVariable String agentId) {
        String userId = UserContext.getCurrentUserId();
        return registry.resolveAgent(agentId, userId)
                .map(a -> RX.ok(a.getAgentCard()))
                .orElse(RX.failA("Agent not found: " + agentId));
    }

    @PostMapping("/{agentId}/ask")
    public RX<A2aTask> askAgent(
            @PathVariable String agentId,
            @RequestBody Map<String, String> body) {
        String userId = UserContext.getCurrentUserId();
        String question = body.get("question");
        if (question == null || question.isBlank()) {
            return RX.failA("question is required");
        }
        A2aAgent agent = registry.resolveAgent(agentId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentId));
        A2aMessage message = A2aMessage.user(List.of(A2aPart.text(question)));
        A2aTask task = agent.sendTask(message);
        return RX.ok(task);
    }
}
