package com.foggy.navigator.tutor.tool.builtin;

import com.foggy.navigator.agent.framework.tool.BuiltInTool;
import com.foggy.navigator.agent.framework.tool.ToolExecutionRequest;
import com.foggy.navigator.agent.framework.tool.ToolExecutionResult;
import com.foggy.navigator.spi.claude.ClaudeWorkerFacade;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 查询 Claude Worker 任务状态
 */
@Slf4j
@Component
public class GetClaudeTaskStatusTool implements BuiltInTool {

    private final ClaudeWorkerFacade claudeWorkerFacade;

    public GetClaudeTaskStatusTool(@Nullable ClaudeWorkerFacade claudeWorkerFacade) {
        this.claudeWorkerFacade = claudeWorkerFacade;
    }

    @Override
    public String getName() {
        return "get_claude_task_status";
    }

    @Override
    public String getDescription() {
        return "查询 Claude Worker 任务的当前状态，包括运行状态、耗时、费用和错误信息";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> taskId = new LinkedHashMap<>();
        taskId.put("type", "string");
        taskId.put("description", "任务 ID（通过 dispatch_claude_worker_task 返回）");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("taskId", taskId);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", new String[]{"taskId"});
        return schema;
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request) {
        if (claudeWorkerFacade == null) {
            log.warn("ClaudeWorkerFacade not available, tool disabled");
            return ToolExecutionResult.error("Claude Worker 模块未启用");
        }
        String taskId = (String) request.getParameters().get("taskId");
        if (taskId == null || taskId.isBlank()) {
            return ToolExecutionResult.error("缺少必填参数: taskId");
        }

        try {
            Map<String, Object> status = claudeWorkerFacade.getTaskStatus(request.getUserId(), taskId);
            StringBuilder sb = new StringBuilder();
            sb.append("任务状态：\n");
            sb.append("- 任务 ID: ").append(status.get("taskId")).append("\n");
            sb.append("- 状态: ").append(status.get("status")).append("\n");
            if (status.get("costUsd") != null) {
                sb.append("- 费用: $").append(status.get("costUsd")).append("\n");
            }
            if (status.get("durationMs") != null) {
                sb.append("- 耗时: ").append(status.get("durationMs")).append("ms\n");
            }
            if (status.get("errorMessage") != null) {
                sb.append("- 错误: ").append(status.get("errorMessage")).append("\n");
            }
            return ToolExecutionResult.success(sb.toString());
        } catch (Exception e) {
            log.error("get_claude_task_status failed: taskId={}", taskId, e);
            return ToolExecutionResult.error("查询任务状态失败：" + e.getMessage());
        }
    }
}
