package com.foggy.navigator.metadata.query.model;

import lombok.Data;

/**
 * 分页参数
 */
@Data
public class Pagination {
    /**
     * 页码（从1开始）
     */
    private Integer page = 1;

    /**
     * 每页大小
     */
    private Integer pageSize = 20;

    /**
     * 获取偏移量（用于SQL OFFSET）
     */
    public int getOffset() {
        return (page - 1) * pageSize;
    }
}
