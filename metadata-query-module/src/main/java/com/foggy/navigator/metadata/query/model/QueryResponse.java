package com.foggy.navigator.metadata.query.model;

import lombok.Data;

/**
 * 查询响应
 */
@Data
public class QueryResponse {
    /**
     * 查询是否成功
     */
    private boolean success;

    /**
     * 错误消息（失败时）
     */
    private String errorMessage;

    /**
     * 查询结果
     */
    private QueryResult result;

    /**
     * 元数据
     */
    private QueryMetadata metadata;

    /**
     * 创建成功响应
     */
    public static QueryResponse success(QueryResult result, QueryMetadata metadata) {
        QueryResponse response = new QueryResponse();
        response.setSuccess(true);
        response.setResult(result);
        response.setMetadata(metadata);
        return response;
    }

    /**
     * 创建失败响应
     */
    public static QueryResponse failure(String errorMessage) {
        QueryResponse response = new QueryResponse();
        response.setSuccess(false);
        response.setErrorMessage(errorMessage);
        return response;
    }
}
