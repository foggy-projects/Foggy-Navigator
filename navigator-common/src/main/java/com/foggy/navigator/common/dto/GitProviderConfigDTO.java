package com.foggy.navigator.common.dto;

import com.foggy.navigator.common.enums.GitProviderType;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Git 提供者配置 DTO（不含敏感信息）
 */
@Data
public class GitProviderConfigDTO {

    private String id;
    private String tenantId;
    private GitProviderType providerType;
    private String baseUrl;
    private String username;
    private Boolean isActive;
    private Boolean hasToken;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
