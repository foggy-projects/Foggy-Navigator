package com.foggy.navigator.tutor.tool.builtin;

import com.foggy.navigator.agent.framework.tool.BuiltInTool;
import com.foggy.navigator.agent.framework.tool.ToolExecutionRequest;
import com.foggy.navigator.agent.framework.tool.ToolExecutionResult;
import com.foggy.navigator.spi.coding.CodingAgentFacade;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 查询编码会话的当前状态和进度
 */
@Slf4j
@Component
public class GetConversationStatusTool implements BuiltInTool {

    private final CodingAgentFacade codingAgentFacade;

    public GetConversationStatusTool(@Nullable CodingAgentFacade codingAgentFacade) {
        this.codingAgentFacade = codingAgentFacade;
    }

    @Override
    public String getName() {
        return "get_conversation_status";
    }

    @Override
    public String getDescription() {
        return "查询编码会话的当前状态和进度";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> conversationId = new LinkedHashMap<>();
        conversationId.put("type", "string");
        conversationId.put("description", "会话 ID");

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("conversationId", conversationId));
        schema.put("required", new String[]{"conversationId"});
        return schema;
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request) {
        if (codingAgentFacade == null) {
            log.warn("CodingAgentFacade not available, tool disabled");
            return ToolExecutionResult.error("编程 Agent 模块未启用");
        }
        String conversationId = (String) request.getParameters().get("conversationId");
        log.info("Executing get_conversation_status for conversationId={}", conversationId);
        try {
            Map<String, Object> result = codingAgentFacade.getConversationStatus(
                    request.getUserId(), conversationId);
            StringBuilder sb = new StringBuilder("会话状态：\n");
            sb.append("- ID: ").append(result.get("id")).append("\n");
            sb.append("- 状态: ").append(result.get("status")).append("\n");
            if (result.containsKey("gitRepoUrl")) {
                sb.append("- Git 仓库: ").append(result.get("gitRepoUrl")).append("\n");
            }
            if (result.containsKey("workingBranch")) {
                sb.append("- 工作分支: ").append(result.get("workingBranch")).append("\n");
            }
            return ToolExecutionResult.success(sb.toString());
        } catch (Exception e) {
            log.error("get_conversation_status failed", e);
            return ToolExecutionResult.error("查询会话状态失败：" + e.getMessage());
        }
    }
}
