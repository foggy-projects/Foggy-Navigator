package com.foggy.navigator.session.controller;

import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.a2a.A2aAgentCard;
import com.foggy.navigator.session.registry.DefaultA2aAgentRegistry;
import com.foggyframework.core.ex.RX;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
}
