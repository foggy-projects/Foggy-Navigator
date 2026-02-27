package com.foggy.navigator.common.dto;

import com.foggy.navigator.common.enums.LlmModelCategory;
import com.foggy.navigator.common.enums.ModelAccessScope;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

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
    private ModelAccessScope scope;
    private List<String> allowedWorkerIds;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
