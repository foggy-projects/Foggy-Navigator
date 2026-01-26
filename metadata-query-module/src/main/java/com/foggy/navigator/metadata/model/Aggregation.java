package com.foggy.navigator.metadata.model;

import lombok.Data;

/**
 * 聚合参数
 */
@Data
public class Aggregation {
    /**
     * 聚合函数：COUNT, SUM, AVG, MAX, MIN
     */
    private String function;

    /**
     * 聚合字段
     */
    private String field;

    /**
     * 分组字段
     */
    private String groupBy;
}
