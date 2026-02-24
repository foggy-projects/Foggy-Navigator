package com.foggy.navigator.common.form;

import com.foggy.navigator.common.enums.LlmModelCategory;
import lombok.Data;

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
     * 模型名称，如 qwen-max（主模型，默认为 opus）
     */
    private String modelName;

    /**
     * Haiku 级别模型名称（用于简单任务）
     */
    private String haikuModelName;

    /**
     * Sonnet 级别模型名称（用于中等复杂度任务）
     */
    private String sonnetModelName;

    /**
     * Opus 级别模型名称（用于复杂任务，默认使用 modelName）
     */
    private String opusModelName;

    /**
     * API Key
     */
    private String apiKey;

    /**
     * 是否设为该 category 的默认模型
     */
    private Boolean isDefault;
}
