package com.foggy.navigator.common.dto;

import com.foggy.navigator.common.enums.AuthType;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * API 凭证 DTO（不含敏感信息）
 */
@Data
public class ApiCredentialDTO {

    private String id;
    private String tenantId;
    private String name;
    private String category;
    private String baseUrl;
    private AuthType authType;
    private String authHeaderName;
    private String description;
    private Boolean isActive;
    private Boolean hasApiKey;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
