package com.foggy.navigator.echo.agent;

import com.foggy.navigator.common.dto.a2a.A2aAgentCard;
import com.foggy.navigator.spi.agent.A2aAgent;
import com.foggy.navigator.spi.agent.A2aAgentProvider;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Echo Agent Provider —— 注册为 @Component 后自动被 UnifiedAgentResolver 发现。
 * <p>
 * 不需要数据库、不需要 Worker、不需要改任何现有代码。
 * 验证了 new-agent-onboarding.md 的"实现 2 个接口 + @Component"承诺。
 */
@Component
public class EchoAgentProvider implements A2aAgentProvider {

    private static final String PROVIDER_TYPE = "echo-agent";
    private static final String AGENT_ID = "echo-agent-default";
    private static final String AGENT_NAME = "Echo Agent";

    @Override
    public String getProviderType() {
        return PROVIDER_TYPE;
    }

    @Override
    public List<A2aAgentCard> listAgentCards(String userId) {
        // Echo Agent 对所有用户可见
        return List.of(new EchoA2aAgent(AGENT_ID, AGENT_NAME).getAgentCard());
    }

    @Override
    public Optional<A2aAgent> resolveAgent(String agentId, String userId) {
        if (AGENT_ID.equals(agentId) || "echo".equals(agentId)) {
            return Optional.of(new EchoA2aAgent(AGENT_ID, AGENT_NAME));
        }
        return Optional.empty();
    }

    @Override
    public List<A2aAgentCard> listAgentCards(AgentResolveContext context) {
        return listAgentCards(context.getUserId());
    }

    @Override
    public Optional<A2aAgent> resolveAgent(String agentId, AgentResolveContext context) {
        return resolveAgent(agentId, context.getUserId());
    }
}
