package com.foggy.navigator.tutor.tool.builtin;

import com.foggy.navigator.agent.framework.tool.BuiltInTool;
import com.foggy.navigator.agent.framework.tool.ToolExecutionRequest;
import com.foggy.navigator.agent.framework.tool.ToolExecutionResult;
import com.foggy.navigator.spi.claude.ClaudeWorkerFacade;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 列出用户的所有 Claude Worker
 */
@Slf4j
@Component
public class ListClaudeWorkersTool implements BuiltInTool {

    private final ClaudeWorkerFacade claudeWorkerFacade;

    public ListClaudeWorkersTool(@Nullable ClaudeWorkerFacade claudeWorkerFacade) {
        this.claudeWorkerFacade = claudeWorkerFacade;
    }

    @Override
    public String getName() {
        return "list_claude_workers";
    }

    @Override
    public String getDescription() {
        return "列出当前用户的所有 Claude Worker 机器，包括名称、ID、状态、地址等信息";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of());
        return schema;
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request) {
        if (claudeWorkerFacade == null) {
            log.warn("ClaudeWorkerFacade not available, tool disabled");
            return ToolExecutionResult.error("Claude Worker 模块未启用");
        }
        try {
            List<Map<String, Object>> workers = claudeWorkerFacade.listWorkers(request.getUserId());
            if (workers.isEmpty()) {
                return ToolExecutionResult.success("当前没有注册的 Claude Worker。请先在「Workers」页面添加 Worker。");
            }
            StringBuilder sb = new StringBuilder();
            sb.append("找到 ").append(workers.size()).append(" 个 Worker：\n\n");
            for (Map<String, Object> w : workers) {
                sb.append("- **").append(w.get("name")).append("**")
                  .append(" (ID: ").append(w.get("workerId")).append(")")
                  .append("\n  状态: ").append(w.get("status"))
                  .append(" | 地址: ").append(w.get("baseUrl"))
                  .append("\n");
            }
            return ToolExecutionResult.success(sb.toString());
        } catch (Exception e) {
            log.error("list_claude_workers failed", e);
            return ToolExecutionResult.error("获取 Worker 列表失败：" + e.getMessage());
        }
    }
}
