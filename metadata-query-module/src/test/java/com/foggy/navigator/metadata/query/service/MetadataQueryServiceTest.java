package com.foggy.navigator.metadata.query.service;

import com.foggy.navigator.metadata.model.*;
import com.foggy.navigator.metadata.query.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * MetadataQueryService 单元测试
 */
@ExtendWith(MockitoExtension.class)
class MetadataQueryServiceTest {

    @Mock
    private RestTemplate restTemplate;

    private MetadataQueryService queryService;

    @BeforeEach
    void setUp() {
        queryService = new MetadataQueryServiceImpl(restTemplate, "http://localhost:8082");
    }

    @Test
    void testExecuteSimpleQuery_Success() {
        // 准备测试数据
        String queryId = "datasource-latest";
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", "tenant-001");

        // Mock Foggy API 响应
        Map<String, Object> foggyResponse = new HashMap<>();
        List<Map<String, Object>> data = new ArrayList<>();
        Map<String, Object> row = new HashMap<>();
        row.put("id", "ds-001");
        row.put("dbType", "MySQL");
        row.put("status", "CONFIGURED");
        data.add(row);
        foggyResponse.put("data", data);
        foggyResponse.put("totalCount", 1);

        ResponseEntity<Map> responseEntity = new ResponseEntity<>(foggyResponse, HttpStatus.OK);

        when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(Map.class)
        )).thenReturn(responseEntity);

        // 执行查询
        QueryResponse response = queryService.executeSimpleQuery(queryId, params);

        // 验证结果
        assertTrue(response.isSuccess());
        assertNotNull(response.getResult());
        assertEquals(1, response.getResult().getRows().size());
        assertEquals("ds-001", response.getResult().getRows().get(0).get("id"));
        assertEquals("MySQL", response.getResult().getRows().get(0).get("dbType"));

        // 验证元数据
        assertNotNull(response.getMetadata());
        assertEquals(queryId, response.getMetadata().getQueryId());
        assertNotNull(response.getMetadata().getExecutionTime());

        // 验证 RestTemplate 被调用
        verify(restTemplate, times(1)).exchange(
                anyString(),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(Map.class)
        );
    }

    @Test
    void testExecuteSimpleQuery_UnknownQueryId() {
        // 测试未知的 queryId
        String queryId = "unknown-query";
        Map<String, Object> params = new HashMap<>();

        // 执行查询
        QueryResponse response = queryService.executeSimpleQuery(queryId, params);

        // 验证结果
        assertFalse(response.isSuccess());
        assertTrue(response.getErrorMessage().contains("Unknown queryId"));

        // 验证 RestTemplate 未被调用
        verify(restTemplate, never()).exchange(
                anyString(),
                any(HttpMethod.class),
                any(HttpEntity.class),
                eq(Map.class)
        );
    }

    @Test
    void testExecuteSimpleQuery_WithPagination() {
        // 准备测试数据
        String queryId = "datasource-list";
        Map<String, Object> params = new HashMap<>();
        params.put("status", "CONFIGURED");
        params.put("page", 1);
        params.put("pageSize", 10);

        // Mock Foggy API 响应
        Map<String, Object> foggyResponse = new HashMap<>();
        List<Map<String, Object>> data = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Map<String, Object> row = new HashMap<>();
            row.put("id", "ds-" + i);
            row.put("status", "CONFIGURED");
            data.add(row);
        }
        foggyResponse.put("data", data);
        foggyResponse.put("totalCount", 25);

        ResponseEntity<Map> responseEntity = new ResponseEntity<>(foggyResponse, HttpStatus.OK);

        when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(Map.class)
        )).thenReturn(responseEntity);

        // 执行查询
        QueryResponse response = queryService.executeSimpleQuery(queryId, params);

        // 验证结果
        assertTrue(response.isSuccess());
        assertEquals(10, response.getResult().getRows().size());
        assertEquals(25L, response.getResult().getTotalCount());

        // 验证分页元数据
        assertEquals(1, response.getMetadata().getCurrentPage());
        assertEquals(10, response.getMetadata().getPageSize());
    }

    @Test
    void testExecuteQuery_AdvancedQuery() {
        // 准备高级查询请求
        QueryRequest request = new QueryRequest();
        request.setQueryName("datasource-list");

        Map<String, Object> filters = new HashMap<>();
        filters.put("status", "CONFIGURED");
        request.setFilters(filters);

        SortCriteria sort = new SortCriteria();
        sort.setField("createdAt");
        sort.setOrder("DESC");
        request.setSort(sort);

        Pagination pagination = new Pagination();
        pagination.setPage(1);
        pagination.setPageSize(20);
        request.setPagination(pagination);

        // Mock Foggy API 响应
        Map<String, Object> foggyResponse = new HashMap<>();
        List<Map<String, Object>> data = new ArrayList<>();
        Map<String, Object> row = new HashMap<>();
        row.put("id", "ds-001");
        row.put("status", "CONFIGURED");
        data.add(row);
        foggyResponse.put("data", data);

        ResponseEntity<Map> responseEntity = new ResponseEntity<>(foggyResponse, HttpStatus.OK);

        when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(Map.class)
        )).thenReturn(responseEntity);

        // 执行查询
        QueryResponse response = queryService.executeQuery(request);

        // 验证结果
        assertTrue(response.isSuccess());
        assertNotNull(response.getResult());
        assertEquals(1, response.getResult().getRows().size());
    }

    @Test
    void testGetAvailableQueries() {
        // 执行查询
        List<QueryInfo> queries = queryService.getAvailableQueries();

        // 验证结果
        assertNotNull(queries);
        assertTrue(queries.size() >= 4); // 至少有4个预定义查询

        // 验证包含特定查询
        boolean hasDatasourceLatest = queries.stream()
                .anyMatch(q -> "datasource-latest".equals(q.getQueryId()));
        assertTrue(hasDatasourceLatest);
    }

    @Test
    void testGetQueryParameters() {
        // 执行查询
        QueryParametersDefinition definition = queryService.getQueryParameters("datasource-list");

        // 验证结果
        assertNotNull(definition);
        assertEquals("datasource-list", definition.getQueryId());
        assertNotNull(definition.getParameters());
        assertTrue(definition.getParameters().size() > 0);

        // 验证参数包含 tenantId
        boolean hasTenantId = definition.getParameters().stream()
                .anyMatch(p -> "tenantId".equals(p.getName()));
        assertTrue(hasTenantId);
    }

    @Test
    void testGetQueryParameters_UnknownQuery() {
        // 执行查询
        QueryParametersDefinition definition = queryService.getQueryParameters("unknown-query");

        // 验证结果
        assertNull(definition);
    }
}
