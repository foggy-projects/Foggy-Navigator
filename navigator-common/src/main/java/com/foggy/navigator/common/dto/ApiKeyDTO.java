package com.foggy.navigator.common.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * API Key DTO
 */
@Data
public class ApiKeyDTO {

    private String id;
    private String userId;
    private String name;

    /**
     * API Key（仅在创建时返回明文，其他时候返回脱敏后的）
     */
    private String apiKey;

    /**
     * 脱敏后的Key（如：sk-***abc123）
     */
    private String maskedApiKey;

    private Boolean enabled;
    private LocalDateTime expiresAt;
    private LocalDateTime lastUsedAt;
    private LocalDateTime createdAt;
}
