package com.foggy.navigator.metadata.query.service;

import com.foggy.navigator.metadata.query.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * 元数据统一查询服务实现
 */
@Slf4j
@Service
public class MetadataQueryServiceImpl implements MetadataQueryService {

    private final RestTemplate restTemplate;
    private final String foggyApiBaseUrl;

    // 预定义查询配置
    private static final Map<String, QueryConfig> QUERY_CONFIGS = new HashMap<>();

    static {
        // 数据源配置查询
        QUERY_CONFIGS.put("datasource-latest", new QueryConfig(
            "datasource-latest", "最新数据源配置", "查询最新的数据源配置",
            Arrays.asList("tenantId"), true, false
        ));
        QUERY_CONFIGS.put("datasource-list", new QueryConfig(
            "datasource-list", "数据源配置列表", "查询所有数据源配置，支持筛选和分页",
            Arrays.asList("tenantId", "status", "dbType", "page", "pageSize"), true, true
        ));

        // 语义层配置查询
        QUERY_CONFIGS.put("semantic-layer-latest", new QueryConfig(
            "semantic-layer-latest", "最新语义层配置", "查询最新的语义层配置",
            Arrays.asList("tenantId"), true, false
        ));
        QUERY_CONFIGS.put("semantic-layer-list", new QueryConfig(
            "semantic-layer-list", "语义层配置列表", "查询所有语义层配置，支持筛选和分页",
            Arrays.asList("tenantId", "datasourceId", "status", "page", "pageSize"), true, true
        ));
    }

    public MetadataQueryServiceImpl(
            RestTemplate restTemplate,
            @Value("${foggy.api.base-url:http://localhost:8082}") String foggyApiBaseUrl) {
        this.restTemplate = restTemplate;
        this.foggyApiBaseUrl = foggyApiBaseUrl;
    }

    @Override
    public QueryResponse executeSimpleQuery(String queryId, Map<String, Object> params) {
        long startTime = System.currentTimeMillis();

        try {
            log.info("Executing simple query: queryId={}, params={}", queryId, params);

            // 验证 queryId
            if (!QUERY_CONFIGS.containsKey(queryId)) {
                return QueryResponse.failure("Unknown queryId: " + queryId);
            }

            // 构建 Foggy API 请求
            String apiUrl = foggyApiBaseUrl + "/jdbc-model/query-model/v2/" + queryId;

            // 构建请求体（Foggy API 格式）
            Map<String, Object> requestBody = buildFoggyRequest(params);

            // 调用 Foggy API
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                apiUrl,
                HttpMethod.POST,
                entity,
                Map.class
            );

            // 解析响应
            QueryResult result = parseFoggyResponse(response.getBody());

            // 构建元数据
            long executionTime = System.currentTimeMillis() - startTime;
            QueryMetadata metadata = new QueryMetadata();
            metadata.setQueryId(queryId);
            metadata.setExecutionTime(executionTime);
            if (params.containsKey("page") && params.containsKey("pageSize")) {
                metadata.setCurrentPage((Integer) params.get("page"));
                metadata.setPageSize((Integer) params.get("pageSize"));
            }

            log.info("Query executed successfully: queryId={}, rows={}, time={}ms",
                    queryId, result.getRows().size(), executionTime);

            return QueryResponse.success(result, metadata);

        } catch (Exception e) {
            log.error("Query execution failed: queryId={}, error={}", queryId, e.getMessage(), e);
            return QueryResponse.failure("Query execution failed: " + e.getMessage());
        }
    }

    @Override
    public QueryResponse executeQuery(QueryRequest queryRequest) {
        long startTime = System.currentTimeMillis();

        try {
            log.info("Executing advanced query: queryName={}, filters={}",
                    queryRequest.getQueryName(), queryRequest.getFilters());

            // 构建 Foggy API 请求
            String apiUrl = foggyApiBaseUrl + "/jdbc-model/query-model/v2/" + queryRequest.getQueryName();

            // 构建请求体
            Map<String, Object> requestBody = new HashMap<>();
            if (queryRequest.getFilters() != null) {
                requestBody.put("filters", queryRequest.getFilters());
            }
            if (queryRequest.getSort() != null) {
                Map<String, Object> order = new HashMap<>();
                order.put("name", queryRequest.getSort().getField());
                order.put("order", queryRequest.getSort().getOrder());
                requestBody.put("orders", Collections.singletonList(order));
            }
            if (queryRequest.getPagination() != null) {
                requestBody.put("page", queryRequest.getPagination().getPage());
                requestBody.put("pageSize", queryRequest.getPagination().getPageSize());
            }

            // 调用 Foggy API
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                apiUrl,
                HttpMethod.POST,
                entity,
                Map.class
            );

            // 解析响应
            QueryResult result = parseFoggyResponse(response.getBody());

            // 构建元数据
            long executionTime = System.currentTimeMillis() - startTime;
            QueryMetadata metadata = new QueryMetadata();
            metadata.setQueryId(queryRequest.getQueryName());
            metadata.setExecutionTime(executionTime);
            if (queryRequest.getPagination() != null) {
                metadata.setCurrentPage(queryRequest.getPagination().getPage());
                metadata.setPageSize(queryRequest.getPagination().getPageSize());
            }

            log.info("Advanced query executed successfully: queryName={}, rows={}, time={}ms",
                    queryRequest.getQueryName(), result.getRows().size(), executionTime);

            return QueryResponse.success(result, metadata);

        } catch (Exception e) {
            log.error("Advanced query execution failed: queryName={}, error={}",
                    queryRequest.getQueryName(), e.getMessage(), e);
            return QueryResponse.failure("Query execution failed: " + e.getMessage());
        }
    }

    @Override
    public List<QueryInfo> getAvailableQueries() {
        List<QueryInfo> queries = new ArrayList<>();

        for (QueryConfig config : QUERY_CONFIGS.values()) {
            QueryInfo info = new QueryInfo();
            info.setQueryId(config.queryId);
            info.setDisplayName(config.displayName);
            info.setDescription(config.description);
            info.setParameters(config.parameters);
            info.setSupportsPagination(config.supportsPagination);
            info.setSupportsSort(config.supportsSort);
            queries.add(info);
        }

        return queries;
    }

    @Override
    public QueryParametersDefinition getQueryParameters(String queryId) {
        QueryConfig config = QUERY_CONFIGS.get(queryId);
        if (config == null) {
            return null;
        }

        QueryParametersDefinition definition = new QueryParametersDefinition();
        definition.setQueryId(queryId);

        List<QueryParametersDefinition.ParameterInfo> params = new ArrayList<>();

        // 根据查询类型定义参数
        for (String paramName : config.parameters) {
            QueryParametersDefinition.ParameterInfo paramInfo = new QueryParametersDefinition.ParameterInfo();
            paramInfo.setName(paramName);
            paramInfo.setRequired(false);

            switch (paramName) {
                case "tenantId":
                    paramInfo.setType("string");
                    paramInfo.setDescription("租户ID");
                    break;
                case "status":
                    paramInfo.setType("string");
                    paramInfo.setDescription("配置状态");
                    break;
                case "dbType":
                    paramInfo.setType("string");
                    paramInfo.setDescription("数据库类型");
                    break;
                case "datasourceId":
                    paramInfo.setType("string");
                    paramInfo.setDescription("数据源ID");
                    break;
                case "page":
                    paramInfo.setType("integer");
                    paramInfo.setDefaultValue(1);
                    paramInfo.setDescription("页码");
                    break;
                case "pageSize":
                    paramInfo.setType("integer");
                    paramInfo.setDefaultValue(20);
                    paramInfo.setDescription("每页大小");
                    break;
            }

            params.add(paramInfo);
        }

        definition.setParameters(params);
        return definition;
    }

    /**
     * 构建 Foggy API 请求
     */
    private Map<String, Object> buildFoggyRequest(Map<String, Object> params) {
        Map<String, Object> request = new HashMap<>();

        // 提取分页参数
        Integer page = null;
        Integer pageSize = null;
        Map<String, Object> filters = new HashMap<>();

        for (Map.Entry<String, Object> entry : params.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if ("page".equals(key)) {
                page = (Integer) value;
            } else if ("pageSize".equals(key)) {
                pageSize = (Integer) value;
            } else {
                // 其他参数作为过滤条件
                filters.put(key, value);
            }
        }

        if (!filters.isEmpty()) {
            request.put("filters", filters);
        }
        if (page != null) {
            request.put("page", page);
        }
        if (pageSize != null) {
            request.put("pageSize", pageSize);
        }

        return request;
    }

    /**
     * 解析 Foggy API 响应
     */
    @SuppressWarnings("unchecked")
    private QueryResult parseFoggyResponse(Map<String, Object> responseBody) {
        QueryResult result = new QueryResult();

        // Foggy API 返回的数据在 "data" 字段中
        if (responseBody.containsKey("data")) {
            Object data = responseBody.get("data");
            if (data instanceof List) {
                result.setRows((List<Map<String, Object>>) data);
            }
        }

        // 总数（如果有）
        if (responseBody.containsKey("totalCount")) {
            result.setTotalCount(((Number) responseBody.get("totalCount")).longValue());
        } else {
            // 如果没有 totalCount，使用数据行数
            result.setTotalCount((long) result.getRows().size());
        }

        return result;
    }

    /**
     * 查询配置
     */
    private static class QueryConfig {
        String queryId;
        String displayName;
        String description;
        List<String> parameters;
        boolean supportsPagination;
        boolean supportsSort;

        QueryConfig(String queryId, String displayName, String description,
                    List<String> parameters, boolean supportsPagination, boolean supportsSort) {
            this.queryId = queryId;
            this.displayName = displayName;
            this.description = description;
            this.parameters = parameters;
            this.supportsPagination = supportsPagination;
            this.supportsSort = supportsSort;
        }
    }
}
