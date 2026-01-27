package com.foggy.navigator.common.form;

import lombok.Data;

/**
 * 语义层配置表单
 * 支持私有Git仓库（GitLab、GitHub、Gitee等）
 */
@Data
public class SemanticLayerConfigForm {
    /**
     * 配置ID（新建时可为空）
     */
    private String id;

    /**
     * 租户ID
     */
    private String tenantId;

    /**
     * 关联的数据源ID
     */
    private String datasourceId;

    /**
     * Git仓库配置
     */
    private GitRepoConfig gitConfig;

    /**
     * 语义层路径配置
     */
    private SemanticLayerPathConfig pathConfig;

    /**
     * 配置描述
     */
    private String description;
}
