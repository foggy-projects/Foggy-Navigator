package com.foggy.navigator.common.dto;

import com.foggy.navigator.common.enums.LlmModelCategory;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * LLM 模型配置 DTO（不含 API Key）
 */
@Data
public class LlmModelConfigDTO {

    private String id;
    private String tenantId;
    private String name;
    private LlmModelCategory category;
    private String baseUrl;
    private String modelName;
    private Boolean isDefault;
    private Boolean hasApiKey;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
