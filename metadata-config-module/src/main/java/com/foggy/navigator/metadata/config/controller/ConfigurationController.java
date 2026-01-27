package com.foggy.navigator.metadata.config.controller;

import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.CurrentUser;
import com.foggy.navigator.common.enums.ConfigItemStatus;
import com.foggy.navigator.common.form.DatasourceConfigForm;
import com.foggy.navigator.common.form.SemanticLayerConfigForm;
import com.foggy.navigator.spi.config.ConfigurationManager;
import com.foggyframework.core.ex.RX;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 配置管理 REST API（写入侧）
 * 查询功能由 MetadataQueryController 提供
 */
@Slf4j
@RestController
@RequestMapping("/api/config")
@RequiredArgsConstructor
@RequireAuth  // 整个 Controller 需要认证
public class ConfigurationController {

    private final ConfigurationManager configManager;

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
