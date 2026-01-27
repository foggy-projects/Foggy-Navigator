package com.foggy.navigator.common.form;

import lombok.Data;

/**
 * 数据源配置表单（二层结构）
 * 第一层：通用信息
 * 第二层：类型特定信息
 */
@Data
public class DatasourceConfigForm {
    /**
     * 数据源ID（新建时可为空，更新时必填）
     */
    private String id;

    /**
     * 租户ID（多租户场景）
     */
    private String tenantId;

    /**
     * 数据源基本信息
     */
    private DatasourceBasicInfo basicInfo;

    /**
     * JDBC类数据源信息（MySQL, PostgreSQL, Oracle, SQL Server等）
     */
    private JdbcDatasourceInfo jdbcInfo;

    /**
     * MongoDB数据源信息（可选）
     */
    private MongoDatasourceInfo mongoInfo;

    // 注：根据 basicInfo.type 决定使用哪个具体配置
}
