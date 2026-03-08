package com.foggy.navigator.agent.framework.router.impl;

import com.foggy.navigator.agent.framework.core.AgentInfo;
import com.foggy.navigator.agent.framework.core.AgentRegistry;
import com.foggy.navigator.agent.framework.protocol.route.*;
import com.foggy.navigator.agent.framework.router.DelegationRequest;
import com.foggy.navigator.agent.framework.router.DelegationResult;
import com.foggy.navigator.agent.framework.router.PreconditionCheckResult;
import com.foggy.navigator.agent.framework.router.SessionRouter;
import com.foggy.navigator.agent.framework.session.Session;
import com.foggy.navigator.agent.framework.session.SessionCreateRequest;
import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.agent.framework.session.SessionStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 默认会话路由器实现
 */
@Component
public class DefaultSessionRouter implements SessionRouter {

    private final SessionManager sessionManager;
    private final AgentRegistry agentRegistry;

    public DefaultSessionRouter(SessionManager sessionManager, AgentRegistry agentRegistry) {
        this.sessionManager = sessionManager;
        this.agentRegistry = agentRegistry;
    }

    @Override
    public DelegationResult delegateToAgent(DelegationRequest request) {
        // 检查目标Agent是否存在
        AgentInfo targetAgent = agentRegistry.findById(request.getTargetAgentId());
        if (targetAgent == null) {
            return DelegationResult.error("Target agent not found: " + request.getTargetAgentId());
        }

        // 更新源会话状态
        if (request.getSourceSessionId() != null) {
            sessionManager.updateStatus(request.getSourceSessionId(), SessionStatus.DELEGATED);
        }

        // 创建新会话
        String newSessionId = sessionManager.createSession(SessionCreateRequest.builder()
                .userId(request.getUserId())
                .tenantId(request.getTenantId())
                .agentId(request.getTargetAgentId())
                .parentSessionId(request.getSourceSessionId())
                .taskName(request.getIntent())
                .build());

        // 构建路由协议
        RoutePayload route = RoutePayload.builder()
                .action(RouteAction.DELEGATE)
                .mode(RouteMode.REPLACE)
                .target(RouteTarget.builder()
                        .agentId(request.getTargetAgentId())
                        .agentName(targetAgent.getName())
                        .sessionId(newSessionId)
                        .build())
                .context(ContextTransfer.builder()
                        .summary(request.getIntent())
                        .variables(request.getParameters())
                        .preserveHistory(true)
                        .build())
                .callback(CallbackConfig.builder()
                        .notifyOnComplete(true)
                        .autoReturn(true)
                        .build())
                .build();

        return DelegationResult.success(newSessionId, route);
    }

    @Override
    public DelegationResult returnToParent(String currentSessionId) {
        Session currentSession = sessionManager.getSession(currentSessionId);
        if (currentSession == null) {
            return DelegationResult.error("Session not found: " + currentSessionId);
        }

        String parentSessionId = currentSession.getParentSessionId();
        if (parentSessionId == null) {
            return DelegationResult.error("No parent session to return to");
        }

        Session parentSession = sessionManager.getSession(parentSessionId);
        if (parentSession == null) {
            return DelegationResult.error("Parent session not found: " + parentSessionId);
        }

        // 关闭当前会话
        sessionManager.closeSession(currentSessionId);

        // 恢复父会话
        sessionManager.updateStatus(parentSessionId, SessionStatus.ACTIVE);

        AgentInfo parentAgent = agentRegistry.findById(parentSession.getAgentId());

        RoutePayload route = RoutePayload.builder()
                .action(RouteAction.RETURN)
                .mode(RouteMode.REPLACE)
                .target(RouteTarget.builder()
                        .agentId(parentSession.getAgentId())
                        .agentName(parentAgent != null ? parentAgent.getName() : null)
                        .sessionId(parentSessionId)
                        .build())
                .build();

        return DelegationResult.success(parentSessionId, route);
    }

    @Override
    public PreconditionCheckResult checkPreconditions(Map<String, Object> preconditions) {
        if (preconditions == null || preconditions.isEmpty()) {
            return PreconditionCheckResult.satisfied();
        }

        List<String> missed = new ArrayList<>();

        for (Map.Entry<String, Object> entry : preconditions.entrySet()) {
            String key = entry.getKey();
            Object expected = entry.getValue();

            // MVP: 简单的布尔检查
            if (expected instanceof Boolean && !((Boolean) expected)) {
                missed.add(key);
            }
        }

        if (missed.isEmpty()) {
            return PreconditionCheckResult.satisfied();
        }

        return PreconditionCheckResult.notSatisfied(missed,
                "Please complete the following before proceeding: " + String.join(", ", missed));
    }
}
