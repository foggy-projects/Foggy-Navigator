package com.foggy.navigator.common.form;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * API Key 创建表单
 */
@Data
public class ApiKeyCreateForm {

    /**
     * API Key 名称/描述
     */
    private String name;

    /**
     * 过期时间（可选）
     */
    private LocalDateTime expiresAt;
}
