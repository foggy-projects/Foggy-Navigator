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
}
