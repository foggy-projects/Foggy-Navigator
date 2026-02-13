package com.foggy.navigator.agent.framework.tool.builtin;

import com.foggy.navigator.agent.framework.tool.BuiltInTool;
import com.foggy.navigator.agent.framework.tool.ToolExecutionRequest;
import com.foggy.navigator.agent.framework.tool.ToolExecutionResult;
import com.foggy.navigator.spi.task.AgentTaskManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 查看当前会话下所有委派任务的状态
 */
@Slf4j
@Component
public class CheckAgentTasksTool implements BuiltInTool {

    private final AgentTaskManager agentTaskManager;

    public CheckAgentTasksTool(@Nullable AgentTaskManager agentTaskManager) {
        this.agentTaskManager = agentTaskManager;
    }

    @Override
    public String getName() {
        return "check_agent_tasks";
    }

    @Override
    public String getDescription() {
        return "查看当前会话下所有委派给其他 Agent 的任务状态。包括编码任务、Claude Worker 任务等。";
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
        if (agentTaskManager == null) {
            return ToolExecutionResult.error("任务追踪功能未启用");
        }

        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return ToolExecutionResult.error("无法获取当前会话 ID");
        }

        try {
            List<Map<String, Object>> tasks = agentTaskManager.listTasksBySession(sessionId);
            if (tasks.isEmpty()) {
                return ToolExecutionResult.success("当前会话没有委派的任务。");
            }

            StringBuilder sb = new StringBuilder();
            sb.append("找到 ").append(tasks.size()).append(" 个委派任务：\n\n");
            for (Map<String, Object> task : tasks) {
                sb.append("- **").append(task.get("taskType")).append("** → ").append(task.get("targetAgentId"));
                sb.append("\n  状态: ").append(task.get("status"));
                sb.append(" | 任务 ID: ").append(task.get("taskId"));
                if (task.get("externalTaskId") != null) {
                    sb.append(" | 外部 ID: ").append(task.get("externalTaskId"));
                }
                if (task.get("resultSummary") != null) {
                    String summary = String.valueOf(task.get("resultSummary"));
                    if (summary.length() > 200) {
                        summary = summary.substring(0, 200) + "...";
                    }
                    sb.append("\n  结果: ").append(summary);
                }
                sb.append("\n");
            }
            return ToolExecutionResult.success(sb.toString());
        } catch (Exception e) {
            log.error("check_agent_tasks failed: sessionId={}", sessionId, e);
            return ToolExecutionResult.error("查询任务状态失败：" + e.getMessage());
        }
    }
}
