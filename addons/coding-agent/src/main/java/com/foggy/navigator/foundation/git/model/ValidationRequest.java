package com.foggy.navigator.foundation.git.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 验证请求
 * 对应验证服务接口: POST /api/semantic-layer/validate
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ValidationRequest {

    /**
     * 文件夹路径（会递归扫描所有 .tm/.qm 文件）
     * 必填
     */
    private String path;

    /**
     * 命名空间（隔离不同环境）
     * 格式建议: {projectId}-{branchName}
     * 可选，默认: openhands
     */
    private String namespace;

    /**
     * 是否监听文件变化
     * 可选，默认: false
     */
    @Builder.Default
    private Boolean watch = false;

    /**
     * 是否清除已存在的同名 Bundle
     * 可选，默认: true
     */
    @Builder.Default
    private Boolean clearExisting = true;

    /**
     * 是否返回堆栈跟踪
     * 可选，默认: false
     */
    @Builder.Default
    private Boolean includeStackTrace = false;
}
