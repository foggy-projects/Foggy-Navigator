package com.foggy.navigator.spi.config;

import com.foggy.navigator.common.enums.ConfigItemStatus;
import com.foggy.navigator.common.form.DatasourceConfigForm;
import com.foggy.navigator.common.form.SemanticLayerConfigForm;

/**
 * 配置管理接口（写入侧）
 * 提供配置项的增删改能力，查询功能由语义层提供
 *
 * 设计原则：
 * - 使用 Form/DTO 而非 Entity 作为参数
 * - 支持部分更新（只传需要修改的字段）
 * - 分层结构：基本信息 + 类型特定信息
 */
public interface ConfigurationManager {

    // ===== 数据源配置 =====

    /**
     * 保存数据源配置
     * @param form 数据源配置表单
     * @return 保存后的配置ID
     */
    String saveDatasourceConfig(DatasourceConfigForm form);

    /**
     * 更新数据源配置
     * @param configId 配置ID
     * @param form 数据源配置表单（仅更新非null字段）
     */
    void updateDatasourceConfig(String configId, DatasourceConfigForm form);

    /**
     * 更新数据源配置状态
     * @param configId 配置ID
     * @param status 新状态
     */
    void updateDatasourceStatus(String configId, ConfigItemStatus status);

    /**
     * 更新数据源连接测试结果
     * @param configId 配置ID
     * @param connectionValid 连接是否有效
     */
    void updateDatasourceConnectionStatus(String configId, boolean connectionValid);

    /**
     * 删除数据源配置
     * @param configId 配置ID
     */
    void deleteDatasourceConfig(String configId);

    // ===== 语义层配置 =====

    /**
     * 保存语义层配置
     * @param form 语义层配置表单
     * @return 保存后的配置ID
     */
    String saveSemanticLayerConfig(SemanticLayerConfigForm form);

    /**
     * 更新语义层配置
     * @param configId 配置ID
     * @param form 语义层配置表单（仅更新非null字段）
     */
    void updateSemanticLayerConfig(String configId, SemanticLayerConfigForm form);

    /**
     * 更新语义层配置状态
     * @param configId 配置ID
     * @param status 新状态
     */
    void updateSemanticLayerStatus(String configId, ConfigItemStatus status);

    /**
     * 更新语义层模型数量
     * @param configId 配置ID
     * @param modelCount 模型数量
     */
    void updateSemanticLayerModelCount(String configId, int modelCount);

    /**
     * 删除语义层配置
     * @param configId 配置ID
     */
    void deleteSemanticLayerConfig(String configId);
}
