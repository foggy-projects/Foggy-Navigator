package com.foggy.navigator.claude.worker.model.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 外部用户映射 DTO
 */
@Data
@Builder
public class ExternalUserDTO {

    /** 第三方员工 ID */
    private String externalUserId;

    /** 第三方员工显示名 */
    private String externalDisplayName;

    /** Navigator 内部用户 ID */
    private String userId;

    /** Navigator 用户名 */
    private String username;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
