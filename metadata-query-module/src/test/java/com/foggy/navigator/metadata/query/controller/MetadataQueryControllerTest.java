package com.foggy.navigator.metadata.query.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.metadata.model.*;
import com.foggy.navigator.metadata.query.model.*;
import com.foggy.navigator.metadata.query.service.MetadataQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.*;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * MetadataQueryController 单元测试
 */
@WebMvcTest(MetadataQueryController.class)
class MetadataQueryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private MetadataQueryService queryService;

    @Test
    void testSimpleQuery_GET() throws Exception {
        // 准备 Mock 响应
        QueryResult result = new QueryResult();
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> row = new HashMap<>();
        row.put("id", "ds-001");
        row.put("dbType", "MySQL");
        rows.add(row);
        result.setRows(rows);
        result.setTotalCount(1L);

        QueryMetadata metadata = new QueryMetadata();
        metadata.setQueryId("datasource-latest");
        metadata.setExecutionTime(15L);

        QueryResponse response = QueryResponse.success(result, metadata);

        when(queryService.executeSimpleQuery(eq("datasource-latest"), anyMap()))
                .thenReturn(response);

        // 执行请求
        mockMvc.perform(get("/api/metadata/query/datasource-latest")
                        .param("tenantId", "tenant-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.rows[0].id").value("ds-001"))
                .andExpect(jsonPath("$.data.rows[0].dbType").value("MySQL"))
                .andExpect(jsonPath("$.data.totalCount").value(1));

        // 验证 Service 被调用
        verify(queryService, times(1))
                .executeSimpleQuery(eq("datasource-latest"), anyMap());
    }

    @Test
    void testSimpleQuery_POST() throws Exception {
        // 准备请求参数
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", "tenant-001");
        params.put("status", "CONFIGURED");

        // 准备 Mock 响应
        QueryResult result = new QueryResult();
        result.setRows(new ArrayList<>());
        result.setTotalCount(0L);

        QueryMetadata metadata = new QueryMetadata();
        metadata.setQueryId("datasource-list");

        QueryResponse response = QueryResponse.success(result, metadata);

        when(queryService.executeSimpleQuery(eq("datasource-list"), anyMap()))
                .thenReturn(response);

        // 执行请求
        mockMvc.perform(post("/api/metadata/query/datasource-list/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(params)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.totalCount").value(0));

        // 验证 Service 被调用
        verify(queryService, times(1))
                .executeSimpleQuery(eq("datasource-list"), anyMap());
    }

    @Test
    void testAdvancedQuery() throws Exception {
        // 准备高级查询请求
        QueryRequest request = new QueryRequest();
        request.setQueryName("datasource-list");

        Map<String, Object> filters = new HashMap<>();
        filters.put("status", "CONFIGURED");
        request.setFilters(filters);

        Pagination pagination = new Pagination();
        pagination.setPage(1);
        pagination.setPageSize(10);
        request.setPagination(pagination);

        // 准备 Mock 响应
        QueryResult result = new QueryResult();
        result.setRows(new ArrayList<>());
        result.setTotalCount(0L);

        QueryMetadata metadata = new QueryMetadata();
        metadata.setQueryId("datasource-list");

        QueryResponse response = QueryResponse.success(result, metadata);

        when(queryService.executeQuery(any(QueryRequest.class)))
                .thenReturn(response);

        // 执行请求
        mockMvc.perform(post("/api/metadata/query/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.totalCount").value(0));

        // 验证 Service 被调用
        verify(queryService, times(1))
                .executeQuery(any(QueryRequest.class));
    }

    @Test
    void testGetAvailableQueries() throws Exception {
        // 准备 Mock 响应
        List<QueryInfo> queries = new ArrayList<>();

        QueryInfo info1 = new QueryInfo();
        info1.setQueryId("datasource-latest");
        info1.setDisplayName("最新数据源配置");
        info1.setDescription("查询最新的数据源配置");
        queries.add(info1);

        QueryInfo info2 = new QueryInfo();
        info2.setQueryId("datasource-list");
        info2.setDisplayName("数据源配置列表");
        info2.setDescription("查询所有数据源配置");
        queries.add(info2);

        when(queryService.getAvailableQueries()).thenReturn(queries);

        // 执行请求
        mockMvc.perform(get("/api/metadata/query/available"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].queryId").value("datasource-latest"))
                .andExpect(jsonPath("$.data[0].displayName").value("最新数据源配置"))
                .andExpect(jsonPath("$.data[1].queryId").value("datasource-list"));

        // 验证 Service 被调用
        verify(queryService, times(1)).getAvailableQueries();
    }

    @Test
    void testGetQueryParameters() throws Exception {
        // 准备 Mock 响应
        QueryParametersDefinition definition = new QueryParametersDefinition();
        definition.setQueryId("datasource-list");

        List<QueryParametersDefinition.ParameterInfo> params = new ArrayList<>();

        QueryParametersDefinition.ParameterInfo param1 = new QueryParametersDefinition.ParameterInfo();
        param1.setName("tenantId");
        param1.setType("string");
        param1.setRequired(false);
        param1.setDescription("租户ID");
        params.add(param1);

        QueryParametersDefinition.ParameterInfo param2 = new QueryParametersDefinition.ParameterInfo();
        param2.setName("status");
        param2.setType("string");
        param2.setRequired(false);
        param2.setDescription("配置状态");
        params.add(param2);

        definition.setParameters(params);

        when(queryService.getQueryParameters("datasource-list"))
                .thenReturn(definition);

        // 执行请求
        mockMvc.perform(get("/api/metadata/query/datasource-list/parameters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.queryId").value("datasource-list"))
                .andExpect(jsonPath("$.data.parameters[0].name").value("tenantId"))
                .andExpect(jsonPath("$.data.parameters[0].type").value("string"))
                .andExpect(jsonPath("$.data.parameters[1].name").value("status"));

        // 验证 Service 被调用
        verify(queryService, times(1))
                .getQueryParameters("datasource-list");
    }

    @Test
    void testSimpleQuery_Failure() throws Exception {
        // 准备失败响应
        QueryResponse response = QueryResponse.failure("Query execution failed");

        when(queryService.executeSimpleQuery(anyString(), anyMap()))
                .thenReturn(response);

        // 执行请求
        mockMvc.perform(get("/api/metadata/query/datasource-latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(600))
                .andExpect(jsonPath("$.msg").value("Query execution failed"));
    }
}
