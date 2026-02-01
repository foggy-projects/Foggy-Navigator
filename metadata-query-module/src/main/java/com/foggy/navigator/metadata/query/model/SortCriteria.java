package com.foggy.navigator.metadata.query.model;

import lombok.Data;

/**
 * 排序条件
 */
@Data
public class SortCriteria {
    /**
     * 排序字段
     */
    private String field;

    /**
     * 排序方式：ASC, DESC
     */
    private String order;
}
