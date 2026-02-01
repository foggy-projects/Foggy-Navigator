package com.foggy.navigator.metadata.query.model;

import lombok.Data;

/**
 * 字段定义
 */
@Data
public class FieldDefinition {
    /**
     * 字段名
     */
    private String name;

    /**
     * 显示名称
     */
    private String displayName;

    /**
     * 数据类型
     */
    private String dataType;

    /**
     * 字段描述
     */
    private String description;
}
