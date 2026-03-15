package com.foggy.navigator.claude.worker.model.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 第三方系统注册结果
 */
@Data
@Builder
public class OpenApiRegisterResultDTO {

    /** 分配的租户 ID */
    private String tenantId;

    /** Navigator 用户 ID */
    private String userId;

    /** 管理员用户名 */
    private String username;

    /** API Key（明文，仅注册时返回一次） */
    private String apiKey;
}
