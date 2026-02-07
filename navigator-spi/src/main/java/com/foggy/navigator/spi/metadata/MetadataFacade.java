package com.foggy.navigator.spi.metadata;

import java.util.List;
import java.util.Map;

/**
 * Metadata 门面接口
 * 供 tutor-agent 的 BuiltInTool 通过 SPI 调用 metadata-config-module 功能，
 * 避免 HTTP 自调用和 ThreadLocal 跨线程问题
 */
public interface MetadataFacade {

    // ===== 数据源 =====

    /**
     * 列出租户下所有数据源配置
     */
    List<Map<String, Object>> listDatasources(String tenantId);

    /**
     * 获取单个数据源详情
     */
    Map<String, Object> getDatasource(String configId);

    /**
     * 保存数据源配置（新建）
     */
    Map<String, Object> saveDatasource(String tenantId, Map<String, Object> params);

    /**
     * 删除数据源配置
     */
    Map<String, Object> deleteDatasource(String configId);

    /**
     * 测试数据源连接
     */
    Map<String, Object> testDatasourceConnection(String configId);

    // ===== 语义层 =====

    /**
     * 列出语义层配置
     */
    List<Map<String, Object>> listSemanticLayers(String tenantId, String datasourceId);

    /**
     * 保存语义层配置（新建）
     */
    Map<String, Object> saveSemanticLayer(String tenantId, Map<String, Object> params);

    /**
     * 删除语义层配置
     */
    Map<String, Object> deleteSemanticLayer(String configId);
}
