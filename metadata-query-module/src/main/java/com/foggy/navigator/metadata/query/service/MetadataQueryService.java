package com.foggy.navigator.metadata.query.service;

import com.foggy.navigator.metadata.query.model.QueryInfo;
import com.foggy.navigator.metadata.query.model.QueryParametersDefinition;
import com.foggy.navigator.metadata.query.model.QueryRequest;
import com.foggy.navigator.metadata.query.model.QueryResponse;

import java.util.List;
import java.util.Map;

/**
 * 元数据统一查询服务
 */
public interface MetadataQueryService {

    // ===== 简单查询接口（推荐） =====

    /**
     * 执行预定义查询（简单接口）
     * @param queryId 查询ID（如 "datasource-latest", "sessions-active"）
     * @param params 查询参数（如 {"tenantId": "tenant-001", "status": "CONFIGURED"}）
     * @return 查询结果
     */
    QueryResponse executeSimpleQuery(String queryId, Map<String, Object> params);

    // ===== 高级查询接口（灵活） =====

    /**
     * 执行完整 DSL 查询（高级接口）
     * @param queryRequest 完整的查询请求（包含过滤、排序、分页、聚合等）
     * @return 查询结果
     */
    QueryResponse executeQuery(QueryRequest queryRequest);

    // ===== 元数据接口 =====

    /**
     * 获取可用的查询ID列表
     * @return 查询ID列表及说明
     */
    List<QueryInfo> getAvailableQueries();

    /**
     * 获取查询参数定义
     * @param queryId 查询ID
     * @return 查询参数定义
     */
    QueryParametersDefinition getQueryParameters(String queryId);
}
