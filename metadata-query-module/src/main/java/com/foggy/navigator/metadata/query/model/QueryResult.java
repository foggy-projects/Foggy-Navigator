package com.foggy.navigator.metadata.query.model;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 查询结果
 */
@Data
public class QueryResult {
    /**
     * 数据行列表
     * 每行是一个 Map<列名, 值>
     */
    private List<Map<String, Object>> rows;

    /**
     * 总行数（分页时使用）
     */
    private Long totalCount;

    /**
     * 聚合结果（如果有）
     */
    private Map<String, Object> aggregationResult;
}
