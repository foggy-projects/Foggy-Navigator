package com.foggy.navigator.common.dto;

import com.foggy.navigator.common.enums.UserStatus;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户DTO（不包含密码等敏感信息）
 */
@Data
public class UserDTO {

    private String id;
    private String tenantId;
    private String username;
    private String email;
    private String displayName;
    private String roles;
    private UserStatus status;
    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
