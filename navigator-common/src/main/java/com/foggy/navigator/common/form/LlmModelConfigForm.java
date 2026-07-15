package com.foggy.navigator.common.form;

import com.foggy.navigator.common.enums.LlmModelCategory;
import com.foggy.navigator.common.enums.ModelAccessScope;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * LLM 模型配置表单
 */
@Data
public class LlmModelConfigForm {

    /**
     * 显示名称，如"通义千问-Max"
     */
    private String name;

    /**
     * 模型类别
     */
    private LlmModelCategory category;

    /**
     * API Base URL
     */
    private String baseUrl;

    /**
     * 模型名称，如 qwen-max
     */
    private String modelName;

    /**
     * API Key
     */
    private String apiKey;

    /**
     * Clear the stored API Key when true.
     */
    private Boolean clearApiKey;

    /**
     * 是否设为该 category 的默认模型
     */
    private Boolean isDefault;

    /**
     * 访问范围
     */
    private ModelAccessScope scope;

    /**
     * 限定访问的 Worker ID 列表（scope=RESTRICTED 时有效）
     */
    private List<String> allowedWorkerIds;

    /**
     * Worker 后端类型（LANGGRAPH_BIZ / CLAUDE_CODE / OPENAI_CODEX /
     * OPENAI_CODEX_APP_SERVER / GEMINI_CLI / null）
     */
    private String workerBackend;

    /** Physical Worker selected for a backend-specific connection test only. */
    private String workerId;

    /**
     * 环境变量（K-V 对），使用该模型启动 Claude Code 时注入到 CLI 子进程
     */
    private Map<String, String> envVars;

    /**
     * 可用模型列表（Claude/Codex 等支持模型变体的后端有效）
     * 为空或 null 表示不限制
     */
    private List<String> availableModels;

    /**
     * LangGraph Biz runtime token budget preset key.
     */
    private String runtimeBudgetPresetKey;

    /**
     * Optional JSON override applied on top of the runtime budget preset.
     */
    private String runtimeBudgetOverrideJson;
}
