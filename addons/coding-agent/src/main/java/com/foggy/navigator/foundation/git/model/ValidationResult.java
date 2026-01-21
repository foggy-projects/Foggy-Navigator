package com.foggy.navigator.foundation.git.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 验证结果
 * TODO: 等待验证服务提供详细的输出规范
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ValidationResult {

    /**
     * 验证是否通过
     */
    private boolean success;

    /**
     * 验证消息
     */
    private String message;

    /**
     * 验证时间
     */
    private LocalDateTime validatedAt;

    /**
     * 错误列表
     * TODO: 根据验证服务的实际输出定义详细的错误结构
     */
    private List<ValidationError> errors;

    /**
     * 创建跳过验证的结果
     */
    public static ValidationResult skipped() {
        return ValidationResult.builder()
                .success(true)
                .message("验证已跳过")
                .validatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 创建错误结果
     */
    public static ValidationResult error(String message) {
        return ValidationResult.builder()
                .success(false)
                .message(message)
                .validatedAt(LocalDateTime.now())
                .build();
    }
}
