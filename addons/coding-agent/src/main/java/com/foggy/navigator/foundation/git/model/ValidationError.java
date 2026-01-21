package com.foggy.navigator.foundation.git.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 验证错误
 * TODO: 等待验证服务提供详细的错误结构
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ValidationError {

    /**
     * 文件路径
     */
    private String file;

    /**
     * 错误类型
     */
    private String type;

    /**
     * 错误信息
     */
    private String message;

    /**
     * 行号 (可选)
     */
    private Integer line;
}
