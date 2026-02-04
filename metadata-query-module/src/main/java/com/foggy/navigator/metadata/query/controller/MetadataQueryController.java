package com.foggy.navigator.metadata.query.controller;

import com.foggy.navigator.metadata.query.model.*;
import com.foggy.navigator.metadata.query.service.MetadataQueryService;
import com.foggyframework.core.ex.RX;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 元数据查询 REST API
 */
@Slf4j
@RestController
@RequestMapping("/api/metadata/query")
@RequiredArgsConstructor
public class MetadataQueryController {

    private final MetadataQueryService queryService;

    // ===== 简单查询接口 =====

    /**
     * 方式1：REST风格（推荐）
     * GET /api/metadata/query/datasource-latest?tenantId=xxx
     */
    @GetMapping("/{queryId}")
    public RX<QueryResult> simpleQuery(
            @PathVariable String queryId,
            @RequestParam Map<String, Object> params) {
        log.info("Simple query (GET): queryId={}, params={}", queryId, params);
        QueryResponse response = queryService.executeSimpleQuery(queryId, params);
        return response.isSuccess()
            ? RX.ok(response.getResult())
            : RX.failB(response.getErrorMessage());
    }

    /**
     * 方式2：POST + JSON（更灵活）
     * POST /api/metadata/query/datasource-latest/execute
     * Body: {"tenantId": "xxx", "status": "CONFIGURED"}
     */
    @PostMapping("/{queryId}/execute")
    public RX<QueryResult> simpleQueryPost(
            @PathVariable String queryId,
            @RequestBody Map<String, Object> params) {
        log.info("Simple query (POST): queryId={}, params={}", queryId, params);
        QueryResponse response = queryService.executeSimpleQuery(queryId, params);
        return response.isSuccess()
            ? RX.ok(response.getResult())
            : RX.failB(response.getErrorMessage());
    }

    // ===== 高级查询接口 =====

    /**
     * 完整 DSL 查询
     * POST /api/metadata/query/execute
     */
    @PostMapping("/execute")
    public RX<QueryResult> advancedQuery(@RequestBody QueryRequest request) {
        log.info("Advanced query: queryName={}, filters={}",
                request.getQueryName(), request.getFilters());
        QueryResponse response = queryService.executeQuery(request);
        return response.isSuccess()
            ? RX.ok(response.getResult())
            : RX.failB(response.getErrorMessage());
    }

    // ===== 元数据接口 =====

    /**
     * 获取可用查询列表
     * GET /api/metadata/query/available
     */
    @GetMapping("/available")
    public RX<List<QueryInfo>> getAvailableQueries() {
        log.info("Get available queries");
        return RX.ok(queryService.getAvailableQueries());
    }

    /**
     * 获取查询参数定义
     * GET /api/metadata/query/{queryId}/parameters
     */
    @GetMapping("/{queryId}/parameters")
    public RX<QueryParametersDefinition> getQueryParameters(@PathVariable String queryId) {
        log.info("Get query parameters: queryId={}", queryId);
        QueryParametersDefinition definition = queryService.getQueryParameters(queryId);
        return definition != null
            ? RX.ok(definition)
            : RX.failB("Unknown queryId: " + queryId);
    }
}
