package com.foggy.navigator.agent.framework.tool.builtin;

import com.foggy.navigator.agent.framework.tool.BuiltInTool;
import com.foggy.navigator.agent.framework.tool.ToolExecutionRequest;
import com.foggy.navigator.agent.framework.tool.ToolExecutionResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 委托工具 - 将任务委托给另一个 Agent 处理
 *
 * LLM 调用此工具时，AgentInvoker 会：
 * 1. 创建新会话（关联父会话）
 * 2. 更新当前会话状态为 DELEGATED
 * 3. 通过 SSE 发送 ROUTE_REQUEST 给前端
 * 4. 前端执行跳转
 */
@Component
public class DelegateTool implements BuiltInTool {

    public static final String TOOL_NAME = "delegate";

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public String getDescription() {
        return "将当前任务委托给另一个专业 Agent 处理。当用户的请求超出你的能力范围，" +
               "或者需要其他 Agent 的专业能力时使用此工具。委托后用户会被引导到新的会话中继续。";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> params = new LinkedHashMap<>();

        // targetAgentId - 目标 Agent ID
        Map<String, Object> targetAgentId = new LinkedHashMap<>();
        targetAgentId.put("type", "string");
        targetAgentId.put("description", "目标 Agent 的 ID，如 'data-analyst-agent', 'coding-agent'");
        params.put("targetAgentId", targetAgentId);

        // intent - 任务意图/摘要
        Map<String, Object> intent = new LinkedHashMap<>();
        intent.put("type", "string");
        intent.put("description", "任务意图的简短描述，会显示给用户并传递给目标 Agent");
        params.put("intent", intent);

        // context - 上下文参数（可选）
        // 注意: LangChain4j 要求 object 类型必须有 properties 字段
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("type", "string");
        context.put("description", "传递给目标 Agent 的上下文参数（JSON 字符串格式），如数据集ID、查询条件等");
        params.put("context", context);

        // background - 后台模式（可选）
        Map<String, Object> background = new LinkedHashMap<>();
        background.put("type", "boolean");
        background.put("description", "是否在后台执行。true 时不跳转，仅创建子会话和任务记录并返回 taskId，可通过 check_agent_tasks 查看进度");
        params.put("background", background);

        // 包装为 JSON Schema 格式
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", params);
        schema.put("required", new String[]{"targetAgentId", "intent"});

        return schema;
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request) {
        // DelegateTool 不在这里执行实际逻辑
        // 实际的委托处理由 AgentInvoker 在检测到此工具调用后执行
        // 这里只返回一个标记，表示需要委托
        Map<String, Object> params = request.getParameters();
        return ToolExecutionResult.success(Map.of(
                "delegationRequired", true,
                "targetAgentId", params.get("targetAgentId"),
                "intent", params.get("intent"),
                "context", params.getOrDefault("context", Map.of())
        ));
    }
}
