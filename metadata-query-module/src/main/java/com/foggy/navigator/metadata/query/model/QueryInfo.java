package com.foggy.navigator.metadata.query.model;

import lombok.Data;

import java.util.List;

/**
 * 查询信息
 */
@Data
public class QueryInfo {
    /**
     * 查询ID
     */
    private String queryId;

    /**
     * 显示名称
     */
    private String displayName;

    /**
     * 描述
     */
    private String description;

    /**
     * 支持的参数列表
     */
    private List<String> parameters;

    /**
     * 是否支持分页
     */
    private Boolean supportsPagination;

    /**
     * 是否支持排序
     */
    private Boolean supportsSort;
}
