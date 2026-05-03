package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.agent.framework.tool.ToolRuntimeContextKeys;
import com.foggy.navigator.agent.framework.tool.ToolRuntimeContextProvider;
import com.foggy.navigator.agent.framework.tool.ToolRuntimeContextRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Provides the task-scoped token to the agent framework runtime context.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BusinessAgentToolRuntimeContextProvider implements ToolRuntimeContextProvider {

    private final BusinessAgentTaskScopedTokenRuntimeStore tokenStore;

    @Override
    public Map<String, Object> provide(ToolRuntimeContextRequest request) {
        String tenantId = request.getTenantId();
        if (tenantId == null || tenantId.isBlank() || request.getSessionId() == null || request.getSessionId().isBlank()) {
            return Map.of();
        }

        String plainToken = tokenStore.getToken(tenantId, request.getSessionId(), request.getTaskId());

        if (plainToken != null) {
            log.debug("Injecting task_scoped_token into runtime context for tool={}, sessionId={}",
                    request.getToolName(), request.getSessionId());
            return Map.of(ToolRuntimeContextKeys.TASK_SCOPED_TOKEN, plainToken);
        }

        return Map.of();
    }
}
