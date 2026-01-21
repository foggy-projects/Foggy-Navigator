package com.foggy.navigator.foundation.git.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 验证请求
 * TODO: 等待验证服务提供详细的输入规范
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ValidationRequest {

    /**
     * 语义层文件目录路径
     */
    private String workspacePath;

    /**
     * 数据源配置 (可选)
     * TODO: 根据验证服务的实际需求定义
     */
    private Object datasource;
}
