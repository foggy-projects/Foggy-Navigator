package com.foggy.navigator.metadata.query.config.controller;

import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.CurrentUser;
import com.foggy.navigator.common.enums.ConfigItemStatus;
import com.foggy.navigator.common.form.DatasourceConfigForm;
import com.foggy.navigator.common.form.SemanticLayerConfigForm;
import com.foggy.navigator.spi.config.ConfigurationManager;
import com.foggy.navigator.spi.metadata.MetadataFacade;
import com.foggyframework.core.ex.RX;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 配置管理 REST API（读写）
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/config")
@RequiredArgsConstructor
@RequireAuth
public class ConfigurationController {

    private final ConfigurationManager configManager;
    private final MetadataFacade metadataFacade;

    // ===== 数据源查询 =====

    /**
     * 列出当前租户所有数据源
     */
    @GetMapping("/datasources")
    public RX<List<Map<String, Object>>> listDatasources() {
        CurrentUser user = UserContext.getCurrentUser();
        return RX.ok(metadataFacade.listDatasources(user.getTenantId()));
    }

    /**
     * 获取单个数据源详情
     */
    @GetMapping("/datasource/{configId}")
    public RX<Map<String, Object>> getDatasource(@PathVariable String configId) {
        return RX.ok(metadataFacade.getDatasource(configId));
    }

    /**
     * 列出语义层（可选按数据源筛选）
     */
    @GetMapping("/semantic-layers")
    public RX<List<Map<String, Object>>> listSemanticLayers(
            @RequestParam(required = false) String datasourceId) {
        CurrentUser user = UserContext.getCurrentUser();
        return RX.ok(metadataFacade.listSemanticLayers(user.getTenantId(), datasourceId));
    }

    /**
     * 测试数据源连接
     */
    @PostMapping("/datasource/{configId}/test-connection")
    public RX<Map<String, Object>> testDatasourceConnection(@PathVariable String configId) {
        log.info("Test datasource connection: id={}", configId);
        return RX.ok(metadataFacade.testDatasourceConnection(configId));
    }

    // ===== 数据源配置 =====

    /**
     * 保存数据源配置
     */
    @PostMapping("/datasource")
    public RX<String> saveDatasourceConfig(@RequestBody DatasourceConfigForm form) {
        CurrentUser user = UserContext.getCurrentUser();
        log.info("Save datasource config: name={}, operator={}",
            form.getBasicInfo() != null ? form.getBasicInfo().getName() : "unknown",
            user.getUsername());

        // 自动填充租户ID（如果未指定且非超级管理员）
        if (form.getTenantId() == null && !user.isSuperAdmin()) {
            form.setTenantId(user.getTenantId());
        }

        String id = configManager.saveDatasourceConfig(form);
        return RX.ok(id);
    }

    /**
     * 更新数据源配置
     */
    @PutMapping("/datasource/{configId}")
    public RX<Void> updateDatasourceConfig(
            @PathVariable String configId,
            @RequestBody DatasourceConfigForm form) {
        log.info("Update datasource config: id={}", configId);
        configManager.updateDatasourceConfig(configId, form);
        return RX.ok();
    }

    /**
     * 更新数据源状态
     */
    @PatchMapping("/datasource/{configId}/status")
    public RX<Void> updateDatasourceStatus(
            @PathVariable String configId,
            @RequestParam ConfigItemStatus status) {
        log.info("Update datasource status: id={}, status={}", configId, status);
        configManager.updateDatasourceStatus(configId, status);
        return RX.ok();
    }

    /**
     * 更新数据源连接状态
     */
    @PatchMapping("/datasource/{configId}/connection")
    public RX<Void> updateDatasourceConnectionStatus(
            @PathVariable String configId,
            @RequestParam boolean valid) {
        log.info("Update datasource connection: id={}, valid={}", configId, valid);
        configManager.updateDatasourceConnectionStatus(configId, valid);
        return RX.ok();
    }

    /**
     * 删除数据源配置
     */
    @DeleteMapping("/datasource/{configId}")
    public RX<Void> deleteDatasourceConfig(@PathVariable String configId) {
        log.info("Delete datasource config: id={}", configId);
        configManager.deleteDatasourceConfig(configId);
        return RX.ok();
    }

    // ===== 语义层配置 =====

    /**
     * 保存语义层配置
     */
    @PostMapping("/semantic-layer")
    public RX<String> saveSemanticLayerConfig(@RequestBody SemanticLayerConfigForm form) {
        log.info("Save semantic layer config: datasourceId={}", form.getDatasourceId());
        String id = configManager.saveSemanticLayerConfig(form);
        return RX.ok(id);
    }

    /**
     * 更新语义层配置
     */
    @PutMapping("/semantic-layer/{configId}")
    public RX<Void> updateSemanticLayerConfig(
            @PathVariable String configId,
            @RequestBody SemanticLayerConfigForm form) {
        log.info("Update semantic layer config: id={}", configId);
        configManager.updateSemanticLayerConfig(configId, form);
        return RX.ok();
    }

    /**
     * 更新语义层状态
     */
    @PatchMapping("/semantic-layer/{configId}/status")
    public RX<Void> updateSemanticLayerStatus(
            @PathVariable String configId,
            @RequestParam ConfigItemStatus status) {
        log.info("Update semantic layer status: id={}, status={}", configId, status);
        configManager.updateSemanticLayerStatus(configId, status);
        return RX.ok();
    }

    /**
     * 更新语义层模型数量
     */
    @PatchMapping("/semantic-layer/{configId}/model-count")
    public RX<Void> updateSemanticLayerModelCount(
            @PathVariable String configId,
            @RequestParam int count) {
        log.info("Update semantic layer model count: id={}, count={}", configId, count);
        configManager.updateSemanticLayerModelCount(configId, count);
        return RX.ok();
    }

    /**
     * 删除语义层配置
     */
    @DeleteMapping("/semantic-layer/{configId}")
    public RX<Void> deleteSemanticLayerConfig(@PathVariable String configId) {
        log.info("Delete semantic layer config: id={}", configId);
        configManager.deleteSemanticLayerConfig(configId);
        return RX.ok();
    }
}
