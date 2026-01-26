package com.foggy.navigator.metadata.model;

import lombok.Data;

import java.util.List;

/**
 * 查询元数据
 */
@Data
public class QueryMetadata {
    /**
     * 查询ID
     */
    private String queryId;

    /**
     * 执行耗时（毫秒）
     */
    private Long executionTime;

    /**
     * 字段定义
     */
    private List<FieldDefinition> fields;

    /**
     * 当前页（分页时）
     */
    private Integer currentPage;

    /**
     * 每页大小（分页时）
     */
    private Integer pageSize;
}
