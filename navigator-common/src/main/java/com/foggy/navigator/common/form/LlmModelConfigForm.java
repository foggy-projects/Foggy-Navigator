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
     * 环境变量（K-V 对），使用该模型启动 Claude Code 时注入到 CLI 子进程
     */
    private Map<String, String> envVars;
}
