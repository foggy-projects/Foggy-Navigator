package com.foggy.navigator.agent.framework.tool;

import java.util.Map;

/**
 * 内置工具接口
 * 系统级工具，对所有 Agent 可用
 */
public interface BuiltInTool {

    /**
     * 工具名称
     */
    String getName();

    /**
     * 工具描述（用于 LLM 理解何时使用）
     */
    String getDescription();

    /**
     * 参数定义（JSON Schema 格式）
     */
    Map<String, Object> getParameters();

    /**
     * 执行工具
     *
     * @param request 执行请求
     * @return 执行结果
     */
    ToolExecutionResult execute(ToolExecutionRequest request);
}
