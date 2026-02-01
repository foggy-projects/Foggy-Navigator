package com.foggy.navigator.metadata.query.model;

import lombok.Data;

import java.util.Map;

/**
 * 查询请求（高级DSL）
 */
@Data
public class QueryRequest {
    /**
     * 查询模型名称（QM文件名，不含.qm后缀）
     */
    private String queryName;

    /**
     * 筛选条件
     * 示例: {"tenant_id": "tenant-001", "status": "CONFIGURED"}
     */
    private Map<String, Object> filters;

    /**
     * 排序字段
     */
    private SortCriteria sort;

    /**
     * 分页参数
     */
    private Pagination pagination;

    /**
     * 聚合参数（可选）
     */
    private Aggregation aggregation;
}
