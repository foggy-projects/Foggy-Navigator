package com.foggy.navigator.common.form;

import lombok.Data;

/**
 * 语义层路径配置
 */
@Data
public class SemanticLayerPathConfig {
    /**
     * 语义层根目录（相对于Git仓库根目录）
     * 示例：semantic-models, models, datasets
     * 默认：semantic-models
     */
    private String rootPath;

    /**
     * TM模型目录（相对于rootPath）
     * 默认：models
     */
    private String modelsPath;

    /**
     * QM查询目录（相对于rootPath）
     * 默认：queries
     */
    private String queriesPath;

    /**
     * 是否自动同步（定时从Git拉取最新）
     */
    private Boolean autoSync;

    /**
     * 同步间隔（分钟，仅当autoSync=true时有效）
     */
    private Integer syncInterval;
}
