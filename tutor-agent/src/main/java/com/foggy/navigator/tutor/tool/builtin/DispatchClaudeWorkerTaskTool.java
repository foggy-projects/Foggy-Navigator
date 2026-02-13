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
 * 向指定 Claude Worker 派发任务
 */
@Slf4j
@Component
public class DispatchClaudeWorkerTaskTool implements BuiltInTool {

    private final ClaudeWorkerFacade claudeWorkerFacade;

    public DispatchClaudeWorkerTaskTool(@Nullable ClaudeWorkerFacade claudeWorkerFacade) {
        this.claudeWorkerFacade = claudeWorkerFacade;
    }

    @Override
    public String getName() {
        return "dispatch_claude_worker_task";
    }

    @Override
    public String getDescription() {
        return "向指定的 Claude Worker 派发编程任务。需要提供 Worker ID 和任务提示词，可选工作目录。";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> workerId = new LinkedHashMap<>();
        workerId.put("type", "string");
        workerId.put("description", "目标 Worker 的 ID（通过 list_claude_workers 获取）");

        Map<String, Object> prompt = new LinkedHashMap<>();
        prompt.put("type", "string");
        prompt.put("description", "任务描述/提示词，详细描述要执行的编程任务");

        Map<String, Object> cwd = new LinkedHashMap<>();
        cwd.put("type", "string");
        cwd.put("description", "Worker 上的工作目录路径（可选）");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("workerId", workerId);
        properties.put("prompt", prompt);
        properties.put("cwd", cwd);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", new String[]{"workerId", "prompt"});
        return schema;
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request) {
        if (claudeWorkerFacade == null) {
            log.warn("ClaudeWorkerFacade not available, tool disabled");
            return ToolExecutionResult.error("Claude Worker 模块未启用");
        }
        Map<String, Object> params = request.getParameters();
        String workerId = (String) params.get("workerId");
        String prompt = (String) params.get("prompt");

        if (workerId == null || workerId.isBlank()) {
            return ToolExecutionResult.error("缺少必填参数: workerId");
        }
        if (prompt == null || prompt.isBlank()) {
            return ToolExecutionResult.error("缺少必填参数: prompt");
        }

        log.info("Dispatching claude worker task: userId={}, workerId={}, promptLength={}",
                request.getUserId(), workerId, prompt.length());

        try {
            // Build params for facade, including tenantId
            Map<String, Object> createParams = new LinkedHashMap<>();
            createParams.put("workerId", workerId);
            createParams.put("prompt", prompt);
            createParams.put("cwd", params.get("cwd"));
            createParams.put("tenantId", request.getTenantId());

            Map<String, Object> result = claudeWorkerFacade.createTask(request.getUserId(), createParams);
            return ToolExecutionResult.success(
                    "任务已派发！\n" +
                    "- 任务 ID: " + result.get("taskId") + "\n" +
                    "- 状态: " + result.get("status") + "\n" +
                    "- Worker: " + workerId + "\n\n" +
                    "可使用 get_claude_task_status 查看任务进度。");
        } catch (Exception e) {
            log.error("dispatch_claude_worker_task failed", e);
            return ToolExecutionResult.error("派发任务失败：" + e.getMessage());
        }
    }
}
