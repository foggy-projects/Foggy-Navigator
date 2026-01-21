package com.foggy.navigator.foundation.git.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 验证结果
 * 对应验证服务接口响应
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
     * 命名空间
     */
    private String namespace;

    /**
     * 总文件数
     */
    private Integer totalFiles;

    /**
     * 有效文件数
     */
    private Integer validFiles;

    /**
     * 无效文件数
     */
    private Integer invalidFiles;

    /**
     * 错误列表
     */
    @Builder.Default
    private List<ValidationError> errors = new ArrayList<>();

    /**
     * 警告列表
     */
    @Builder.Default
    private List<ValidationError> warnings = new ArrayList<>();

    /**
     * 验证耗时（毫秒）
     */
    private Long durationMs;

    /**
     * 创建跳过验证的结果
     */
    public static ValidationResult skipped() {
        return ValidationResult.builder()
                .success(true)
                .totalFiles(0)
                .validFiles(0)
                .invalidFiles(0)
                .build();
    }

    /**
     * 创建错误结果
     */
    public static ValidationResult error(String message) {
        ValidationError error = ValidationError.builder()
                .message(message)
                .build();

        return ValidationResult.builder()
                .success(false)
                .totalFiles(0)
                .validFiles(0)
                .invalidFiles(0)
                .errors(List.of(error))
                .build();
    }
}
