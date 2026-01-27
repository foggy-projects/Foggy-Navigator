package com.foggy.navigator.common.form;

import com.foggy.navigator.common.enums.ConfigItemStatus;
import com.foggy.navigator.common.enums.DatasourceType;
import lombok.Data;

/**
 * 数据源基本信息
 */
@Data
public class DatasourceBasicInfo {
    /**
     * 数据源名称
     */
    private String name;

    /**
     * 数据源类型：JDBC, MONGO, REDIS, ELASTICSEARCH等
     */
    private DatasourceType type;

    /**
     * 配置描述
     */
    private String description;

    /**
     * 配置状态（可选，默认为NOT_STARTED）
     */
    private ConfigItemStatus status;
}
